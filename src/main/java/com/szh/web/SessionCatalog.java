package com.szh.web;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.szh.context.dto.UserMessageItem;
import com.szh.event.Event;
import com.szh.event.EventEnum;
import com.szh.event.TokenUsageEvent;
import com.szh.event.UserMessageEvent;
import com.szh.store.EventStore;
import com.szh.store.EventStoreFactory;
import com.szh.store.FileEventStore;
import com.szh.store.StoreEnum;
import com.szh.store.db.AgentEventPO;
import com.szh.store.db.AgentEventMapper;
import com.szh.store.db.MyBatisPlusHolder;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.session.SqlSession;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 会话目录（只读）：列出所有历史会话并读取其事件流，是 Web 查看器的唯一数据入口。
 * <p>
 * 为什么单独写一层而不直接调 {@link EventStore#getEvents}：
 * <ul>
 *   <li><b>列会话</b>是 {@link EventStore} 接口没覆盖的能力（它按已知 sessionId 取事件）。FILE 引擎扫目录即可；
 *       MYSQL 引擎用一条 group by 聚合查询拿到全部 sessionId + 规模统计（复用已公开的
 *       {@link MyBatisPlusHolder#getSqlSessionFactory()}，不新增 mapper 方法、不改既有持久化代码）。</li>
 *   <li><b>轮询缓存</b>：前端默认 3s 自动刷新，而单个会话的 JSONL 可达数百 KB。这里按「文件大小 + mtime」
 *       做进程内缓存，文件没变就直接复用已解析的事件列表，避免反复解析大文件。</li>
 * </ul>
 * 全部操作都是只读的，绝不写事件日志（查看器不参与 agent 运行）。
 *
 * @author demussong
 * @date 2026/10/15
 */
@Slf4j
public class SessionCatalog {

    private static final String SUB_SESSION_MARK = "-sub-";

    private final EventStore eventStore;

    /**
     * 会话 id -> 已解析事件（仅 FILE 引擎缓存，键含文件大小与 mtime，文件一变即失效）
     */
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public SessionCatalog() {
        // 每个查看器进程持有一个 store 实例并复用：MEMORY 引擎下才能看到本进程写入的事件
        this.eventStore = EventStoreFactory.createEventStore();
    }

    /**
     * 仅供展示的存储引擎标识（MEMORY 时不具备跨进程持久化，UI 会给出提示）
     */
    public StoreEnum storeType() {
        return eventStore.getStoreType();
    }

    /**
     * 会话文件目录（仅 FILE 引擎有值），UI 页脚展示用
     */
    public Path sessionDir() {
        return eventStore instanceof FileEventStore fileStore ? fileStore.getSessionDir() : null;
    }

    /**
     * 会话清单，按最近活动时间倒序；每项含规模统计与最近一次用户输入（便于在列表里认出会话）。
     */
    public List<Map<String, Object>> list() {
        if (storeType() == StoreEnum.MYSQL) {
            List<Map<String, Object>> summaries = mysqlSummaries();
            summaries.sort(Comparator.comparingLong((Map<String, Object> m) -> longOf(m.get("endTime"))).reversed());
            return summaries;
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (String sessionId : sessionIds()) {
            out.add(summary(sessionId));
        }
        out.sort(Comparator.comparingLong((Map<String, Object> m) -> longOf(m.get("endTime"))).reversed());
        return out;
    }

    /**
     * 某会话的全部事件，按时间升序（与 {@code TraceReplay} 同口径）
     */
    public List<Event> events(String sessionId) {
        Path file = filePath(sessionId);
        long size = -1L;
        long mtime = -1L;
        if (file != null) {
            try {
                if (Files.isRegularFile(file)) {
                    size = Files.size(file);
                    mtime = Files.getLastModifiedTime(file).toMillis();
                }
            } catch (IOException e) {
                log.debug("SessionCatalog: stat session file failed: {}", file, e);
            }
        }
        CacheEntry cached = cache.get(sessionId);
        if (cached != null && size >= 0 && cached.size() == size && cached.mtime() == mtime) {
            return cached.events;
        }
        List<Event> events = new ArrayList<>(eventStore.getEvents(sessionId));
        events.sort(Comparator.comparingLong(Event::getTimestamp));
        if (size >= 0) {
            cache.put(sessionId, new CacheEntry(size, mtime, List.copyOf(events)));
        }
        return events;
    }

    /**
     * 会话是否存在（用于 API 404 判定；FILE 引擎走廉价的存在性检查）
     */
    public boolean exists(String sessionId) {
        return eventStore.exists(sessionId);
    }

    // ==================== 内部实现 ====================

    private record CacheEntry(long size, long mtime, List<Event> events) {
    }

    /**
     * FILE 引擎的会话 id 清单：扫会话目录下的 *.jsonl（文件名即 sessionId）
     */
    private List<String> sessionIds() {
        Path dir = sessionDir();
        if (dir == null || !Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(name -> name.endsWith(".jsonl"))
                    .map(name -> name.substring(0, name.length() - ".jsonl".length()))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            log.warn("SessionCatalog: list session dir failed: {}", dir, e);
            return List.of();
        }
    }

    /**
     * 从事件流聚合出会话摘要（FILE / MEMORY 引擎）
     */
    private Map<String, Object> summary(String sessionId) {
        List<Event> events = events(sessionId);
        Map<String, Object> data = baseSummary(sessionId, events.size());
        data.put("runs", (int) events.stream().map(Event::getRunId).filter(Objects::nonNull).distinct().count());
        data.put("turns", (int) events.stream().map(Event::getTurnId).filter(Objects::nonNull).distinct().count());
        long start = events.isEmpty() ? 0L : events.get(0).getTimestamp();
        long end = events.isEmpty() ? 0L : events.get(events.size() - 1).getTimestamp();
        data.put("startTime", start);
        data.put("endTime", end);
        long[] tokens = sumTokens(events);
        data.put("promptTokens", tokens[0]);
        data.put("completionTokens", tokens[1]);
        data.put("totalTokens", tokens[2]);
        data.put("cachedTokens", tokens[3]);
        data.put("tokenRounds", tokens[4]);
        data.put("lastUserInput", lastUserInput(events));
        Path file = filePath(sessionId);
        try {
            data.put("bytes", file != null && Files.isRegularFile(file) ? Files.size(file) : 0L);
        } catch (IOException e) {
            data.put("bytes", 0L);
        }
        return data;
    }

    /**
     * MYSQL 引擎的会话清单：一条 group by 聚合查询拿全（按 session_id 分组），
     * 不在列表页逐会话扫表。token 用量不落库表列、payload 解析依赖方言，故列表页留空（详情页仍能算全）。
     */
    private List<Map<String, Object>> mysqlSummaries() {
        List<Map<String, Object>> out = new ArrayList<>();
        try (SqlSession session = MyBatisPlusHolder.getSqlSessionFactory().openSession(true)) {
            AgentEventMapper mapper = session.getMapper(AgentEventMapper.class);
            QueryWrapper<AgentEventPO> wrapper = new QueryWrapper<>();
            wrapper.select("session_id", "count(*) as events", "count(distinct run_id) as runs",
                            "count(distinct turn_id) as turns", "min(event_time) as firstTime", "max(event_time) as lastTime")
                    .groupBy("session_id");
            for (Map<String, Object> row : mapper.selectMaps(wrapper)) {
                String sessionId = strOf(pick(row, "session_id"));
                Map<String, Object> data = baseSummary(sessionId, intOf(pick(row, "events")));
                data.put("runs", intOf(pick(row, "runs")));
                data.put("turns", intOf(pick(row, "turns")));
                data.put("startTime", longOf(pick(row, "firstTime")));
                data.put("endTime", longOf(pick(row, "lastTime")));
                data.put("promptTokens", null);
                data.put("completionTokens", null);
                data.put("totalTokens", null);
                data.put("cachedTokens", null);
                // 聚合查询不解析 payload，token 字段一律 null（UI 统显「未记录」，详情页仍能算出全量）
                data.put("tokenRounds", null);
                data.put("lastUserInput", null);
                data.put("bytes", 0L);
                out.add(data);
            }
        } catch (RuntimeException e) {
            log.warn("SessionCatalog: mysql session list failed", e);
        }
        return out;
    }

    /**
     * 各引擎共用的基础字段：会话标识、事件数、子会话标记（子 agent 的 sessionId 形如 {@code {parent}-sub-xxxxxxxx}）
     */
    private Map<String, Object> baseSummary(String sessionId, int eventCount) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", sessionId);
        data.put("events", eventCount);
        data.put("sub", sessionId != null && sessionId.contains(SUB_SESSION_MARK));
        if (sessionId != null && sessionId.contains(SUB_SESSION_MARK)) {
            data.put("parent", sessionId.substring(0, sessionId.indexOf(SUB_SESSION_MARK)));
        }
        return data;
    }

    private long[] sumTokens(List<Event> events) {
        // [0]prompt [1]completion [2]total [3]cached [4]记录了 token 的轮数
        long[] sum = new long[5];
        for (Event e : events) {
            if (e instanceof TokenUsageEvent t) {
                sum[4]++;
                sum[0] += t.getPromptTokens();
                sum[1] += t.getCompletionTokens();
                sum[2] += t.getTotalTokens();
                sum[3] += t.getCachedTokens();
            }
        }
        return sum;
    }

    /**
     * 最近一次用户输入（截断），让会话列表一眼能认出「这个会话是干嘛的」
     */
    private String lastUserInput(List<Event> events) {
        for (int i = events.size() - 1; i >= 0; i--) {
            Event e = events.get(i);
            if (e.getType() == EventEnum.USER_INPUT && e instanceof UserMessageEvent ume
                    && ume.getMessageItem() instanceof UserMessageItem item) {
                String text = item.getContent() == null ? "" : item.getContent().replaceAll("\\s+", " ").trim();
                return text.length() > 120 ? text.substring(0, 120) + "…" : text;
            }
        }
        return null;
    }

    /**
     * 定位会话文件（FILE 引擎）；sessionId 一律先 sanitize，杜绝路径穿越
     */
    private Path filePath(String sessionId) {
        if (eventStore instanceof FileEventStore fileStore && fileStore.getSessionDir() != null) {
            return fileStore.getSessionDir().resolve(sanitize(sessionId) + ".jsonl");
        }
        return null;
    }

    /**
     * 与 {@code FileEventStore.sanitize} 同款：把 sessionId 规范成文件系统安全片段。
     * 查看器的 sessionId 来自 URL，必须过滤后再参与路径拼接。
     */
    public static String sanitize(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return "default";
        }
        String cleaned = sessionId.trim().replaceAll("[^a-zA-Z0-9_-]", "_");
        return cleaned.isEmpty() ? "default" : cleaned;
    }

    /**
     * 大小写不敏感地取聚合行字段：不同驱动/MyBatis 配置下列标签大小写可能不一致
     */
    private static Object pick(Map<String, Object> row, String key) {
        if (row == null) {
            return null;
        }
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(key)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static long longOf(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    private static int intOf(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }

    private static String strOf(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
