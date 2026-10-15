package com.szh.web;

import com.szh.trace.context.ContextSnapshot;
import com.szh.trace.context.ContextSnapshotStore;
import com.szh.utils.JsonUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 上下文快照读侧：读取 {@link ContextSnapshotStore} 写下的「每轮实际发给模型的上下文」侧车文件。
 * <p>
 * 与写侧共用同一套路径规则（同一个 {@link ContextSnapshotStore} 实例负责定位），避免两侧各拼一遍路径而漂移。
 * 文件缺失或某轮无快照时返回 null / 空集合——历史会话（该能力上线前）本就没有快照，查看器会退化为
 * 「由事件流重建」的近似结果（见 {@link ContextReplayer}）。
 *
 * @author demussong
 * @date 2026/10/15
 */
@Slf4j
public class ContextSnapshotReader {

    private final ContextSnapshotStore store = new ContextSnapshotStore();

    /**
     * 快照能力是否启用（关闭时 run 不写快照，UI 应统一显示「无快照，已回退重建」）
     */
    public boolean enabled() {
        return store.isEnabled();
    }

    /**
     * 快照根目录，UI 页脚展示用
     */
    public Path rootDir() {
        return store.rootDir();
    }

    /**
     * 读取某 run 某轮的完整上下文快照；文件不存在或无该轮则返回 null
     */
    public ContextSnapshot read(String sessionId, String runId, int round) {
        if (runId == null) {
            return null;
        }
        Path file = store.file(sessionId, runId);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (String line : lines) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                ContextSnapshot snapshot = JsonUtil.parse(line, ContextSnapshot.class);
                if (snapshot != null && snapshot.round() == round) {
                    return snapshot;
                }
            }
        } catch (IOException | RuntimeException e) {
            log.warn("ContextSnapshotReader: read snapshot failed, session={}, run={}, round={}",
                    sessionId, runId, round, e);
        }
        return null;
    }

    /**
     * 某 run 有哪些轮次存在快照：UI 据此标注每轮上下文是「精确快照」还是「事件流重建」
     */
    public Set<Integer> rounds(String sessionId, String runId) {
        Set<Integer> rounds = new LinkedHashSet<>();
        if (runId == null) {
            return rounds;
        }
        Path file = store.file(sessionId, runId);
        if (!Files.isRegularFile(file)) {
            return rounds;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                ContextSnapshot snapshot = JsonUtil.parse(line, ContextSnapshot.class);
                if (snapshot != null) {
                    rounds.add(snapshot.round());
                }
            }
        } catch (IOException | RuntimeException e) {
            log.warn("ContextSnapshotReader: list snapshot rounds failed, session={}, run={}",
                    sessionId, runId, e);
        }
        return rounds;
    }
}
