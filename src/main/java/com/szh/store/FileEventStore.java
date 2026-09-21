package com.szh.store;

import com.szh.event.Event;
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
 * 文件事件存储：借鉴 Codex 的会话 rollout 设计，把每个 session 的事件按追加方式写成 JSONL，
 * 一行一个事件（{@link SerializedEvent} 的 JSON）。零外部依赖即可持久化并支持断点恢复。
 * <p>
 * 存储路径：{@code {store.file.dir}/{sessionId}.jsonl}，默认目录 {@code ~/.openagent/sessions}。
 * 追加写失败会抛 RuntimeException，交由 {@code AgentState.applyEvent} 捕获并标记为非持久。
 *
 * @author demussong
 * @date 2026/9/21
 */
@Slf4j
public class FileEventStore implements EventStore {

    private static final String DIR_KEY = "store.file.dir";
    private static final String DEFAULT_DIR = "~/.openagent/sessions";

    private final Path sessionDir;

    public FileEventStore() {
        this.sessionDir = expandHome(ConfigUtil.get(DIR_KEY, DEFAULT_DIR));
        try {
            Files.createDirectories(sessionDir);
        } catch (IOException | RuntimeException e) {
            log.error("FileEventStore: failed to create session dir: {}", sessionDir, e);
        }
    }

    @Override
    public void appendEvent(Event event) {
        Path file = sessionFile(event.getSessionId());
        String line = JsonUtil.toJson(EventJsonCodec.serialize(event));
        if (line == null) {
            log.error("FileEventStore: serialize event failed, type={}, eventId={}",
                    event.getType(), event.getId());
            return;
        }
        try {
            Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
            // 抛出交由上层判定持久化失败（与 MySQL 写库失败行为一致）
            throw new RuntimeException("FileEventStore: append event failed: " + file, e);
        }
    }

    @Override
    public StoreEnum getStoreType() {
        return StoreEnum.FILE;
    }

    @Override
    public boolean exists(String sessionId) {
        Path file = sessionFile(sessionId);
        try {
            return Files.exists(file) && Files.size(file) > 0;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    @Override
    public List<Event> getEvents(String sessionId) {
        Path file = sessionFile(sessionId);
        List<Event> events = new ArrayList<>();
        if (!Files.exists(file)) {
            return events;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                SerializedEvent se = JsonUtil.parse(line, SerializedEvent.class);
                Event event = EventJsonCodec.deserialize(se);
                if (event != null) {
                    events.add(event);
                }
            }
        } catch (IOException | RuntimeException e) {
            log.error("FileEventStore: read session file failed: {}", file, e);
        }
        return events;
    }

    private Path sessionFile(String sessionId) {
        return sessionDir.resolve(sanitize(sessionId) + ".jsonl");
    }

    /**
     * 规范化 sessionId 为文件系统安全的文件名，避免路径穿越；空值兜底为 default
     */
    private static String sanitize(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return "default";
        }
        String cleaned = sessionId.trim().replaceAll("[^a-zA-Z0-9_-]", "_");
        return cleaned.isEmpty() ? "default" : cleaned;
    }

    private static Path expandHome(String path) {
        if (path != null && path.startsWith("~")) {
            return Path.of(System.getProperty("user.home"), path.substring(1).replaceFirst("^/", ""));
        }
        return Path.of(path);
    }
}
