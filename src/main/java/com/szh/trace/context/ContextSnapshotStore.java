package com.szh.trace.context;

import com.szh.context.dto.MessageItem;
import com.szh.tool.Tool;
import com.szh.utils.ConfigUtil;
import com.szh.utils.JsonUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 每轮上下文快照的写侧存储（读侧见 {@code com.szh.web.ContextSnapshotReader}）。
 * <p>
 * 设计要点：
 * <ul>
 *   <li><b>与事件日志物理分离</b>：快照按轮全量复制上下文，体积远大于事件，混进事件日志会让断点恢复、
 *       回溯截断、TraceReplay 全部背上无关负担，故单独落 {@code {trace.context.dir}}。</li>
 *   <li><b>默认目录是全局的</b>（{@code ~/.openagent/context}，与 {@code store.file.dir} 同哲学）：
 *       若跟着工作区走，换个目录启动 agent 查看器就找不到快照、只剩事件流重建的近似结果。</li>
 *   <li><b>布局</b>：{@code {dir}/{sessionId}/{runId}.jsonl}，一行一轮（{@link ContextSnapshot} 的 JSON）。
 *       会话列出=列目录，取某轮=读单个小文件，避免为了看一轮而解析整份大文件。</li>
 *   <li><b>永不抛异常</b>：快照是诊断能力，写失败/超限只告警——绝不能因为查看器相关逻辑拖垮 run
 *       （与 {@code ToolResultStore} 同款防御）。</li>
 * </ul>
 *
 * @author demussong
 * @date 2026/10/15
 */
@Slf4j
public class ContextSnapshotStore {

    private static final String ENABLED_KEY = "trace.context.enabled";

    private static final String DIR_KEY = "trace.context.dir";

    private static final String DEFAULT_DIR = "~/.openagent/context";

    /**
     * 单条快照（一行）的体积上限：超限说明上下文异常巨大，跳过并告警，避免单行 JSON 撑爆读取方
     */
    private static final String MAX_BYTES_KEY = "trace.context.max.bytes";

    private static final int DEFAULT_MAX_BYTES = 2 * 1024 * 1024;

    private final boolean enabled;

    private final Path rootDir;

    private final int maxBytes;

    public ContextSnapshotStore() {
        this.enabled = ConfigUtil.getBoolean(ENABLED_KEY, true);
        this.rootDir = expandHome(ConfigUtil.get(DIR_KEY, DEFAULT_DIR));
        this.maxBytes = ConfigUtil.getInt(MAX_BYTES_KEY, DEFAULT_MAX_BYTES);
    }

    /**
     * 记录一轮的完整上下文快照。任何异常都在内部消化（只告警），调用方无需 try-catch。
     *
     * @param callContext 实际传给 model.call 的上下文（含运行期装饰后的 system prompt）
     * @param tools       本轮模型可见的工具列表
     */
    public void record(String sessionId, String runId, String turnId, int round,
                       List<MessageItem> callContext, List<Tool> tools) {
        if (!enabled || callContext == null) {
            return;
        }
        try {
            List<SnapshotMessage> messages = new ArrayList<>(callContext.size());
            for (MessageItem item : callContext) {
                messages.add(SnapshotMessage.from(item));
            }
            List<String> toolCodes = tools == null ? List.of()
                    : tools.stream().map(Tool::getCode).filter(c -> c != null).toList();
            ContextSnapshot snapshot = new ContextSnapshot(sessionId, runId, turnId, round,
                    System.currentTimeMillis(), messages.size(), estimate(messages), toolCodes, messages);
            String line = JsonUtil.toJson(snapshot);
            if (line == null) {
                log.warn("ContextSnapshotStore: serialize failed, session={}, run={}, round={}",
                        sessionId, runId, round);
                return;
            }
            long bytes = line.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > maxBytes) {
                log.warn("ContextSnapshotStore: snapshot too large ({} bytes > {}), skipped: session={}, run={}, round={}",
                        bytes, maxBytes, sessionId, runId, round);
                return;
            }
            Path file = file(sessionId, runId);
            Files.createDirectories(file.getParent());
            Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
            log.warn("ContextSnapshotStore: record failed, session={}, run={}, round={}",
                    sessionId, runId, round, e);
        }
    }

    /**
     * 估算上下文 token：复用已落地的逐条估算值求和，避免重复遍历消息文本
     */
    private static int estimate(List<SnapshotMessage> messages) {
        int total = 0;
        for (SnapshotMessage m : messages) {
            total += m.estimatedTokens();
        }
        return total;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 快照根目录（查看器据此定位全部会话的快照）
     */
    public Path rootDir() {
        return rootDir;
    }

    /**
     * 某会话的快照目录：{@code {root}/{sessionId}}
     */
    public Path sessionDir(String sessionId) {
        return rootDir.resolve(sanitize(sessionId));
    }

    /**
     * 某 run 的快照文件：{@code {root}/{sessionId}/{runId}.jsonl}
     */
    public Path file(String sessionId, String runId) {
        return sessionDir(sessionId).resolve(sanitize(runId) + ".jsonl");
    }

    /**
     * 规范化 id 为文件系统安全的文件名（事件 store 同款口径），防路径穿越
     */
    private static String sanitize(String id) {
        if (id == null || id.isBlank()) {
            return "default";
        }
        String cleaned = id.trim().replaceAll("[^a-zA-Z0-9_-]", "_");
        return cleaned.isEmpty() ? "default" : cleaned;
    }

    private static Path expandHome(String path) {
        if (path != null && path.startsWith("~")) {
            return Path.of(System.getProperty("user.home"), path.substring(1).replaceFirst("^/", ""));
        }
        return Path.of(path);
    }
}
