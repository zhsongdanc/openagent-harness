package com.szh.memory;

import com.szh.context.compaction.Summarizer;
import com.szh.context.dto.MessageItem;
import com.szh.context.dto.SystemMessageItem;
import com.szh.utils.ConfigUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 长期记忆门面（L4）：对运行时暴露一个极简、稳定、绝不抛异常的入口，
 * 内部封装 SQLite FTS5 存储、检索召回与反思抽取。
 * <p>
 * 采用懒加载单例，从配置读取开关与参数；<b>任何初始化或运行失败都优雅降级为“记忆功能关闭”</b>，
 * 保证即便本机未就绪 SQLite，也不会影响主 Agent 循环。
 * <p>
 * 三个能力：
 * <ul>
 *   <li>{@link #remember(Memory)} / {@link #rememberAll(List)}：写入记忆（带去重更新与容量淘汰）；</li>
 *   <li>{@link #injectRecall(List, String)} / {@link #recall(String, MemoryScope, int)}：按 query 召回；</li>
 *   <li>{@link #forget(String)} / {@link #forgetByQuery(String, MemoryScope, int)}：遗忘（供工具主动调用）；</li>
 *   <li>{@link #reflectAndStore(String, List, Summarizer)}：run 结束后蒸馏对话为记忆。</li>
 * </ul>
 *
 * @author demussong
 * @date 2026/9/21
 */
@Slf4j
public class LongTermMemory {

    private static volatile LongTermMemory instance;

    private final boolean enabled;
    private final boolean retrievalEnabled;
    private final boolean reflectionEnabled;
    private final int topK;
    /** 写入去重开关：命中近似既有记忆时更新而非新增 */
    private final boolean dedupEnabled;
    /** 去重相似度阈值 [0,1]，达到即判为重复 */
    private final double dedupThreshold;
    /** 去重时拉取的候选条数 */
    private final int dedupCandidates;
    /** 记忆库容量上限，<=0 表示不限；超限按最旧淘汰 */
    private final int capacityMax;
    /** 存储初始化失败时为 null，所有操作自动变为 no-op */
    private final MemoryStore store;

    private LongTermMemory() {
        this.enabled = ConfigUtil.getBoolean("memory.enabled", true);
        this.retrievalEnabled = ConfigUtil.getBoolean("memory.retrieval.enabled", true);
        this.reflectionEnabled = ConfigUtil.getBoolean("memory.reflection.enabled", true);
        this.topK = ConfigUtil.getInt("memory.retrieval.topK", 5);
        this.dedupEnabled = ConfigUtil.getBoolean("memory.dedup.enabled", true);
        this.dedupThreshold = ConfigUtil.getDouble("memory.dedup.threshold", 0.72);
        this.dedupCandidates = ConfigUtil.getInt("memory.dedup.candidates", 8);
        this.capacityMax = ConfigUtil.getInt("memory.capacity.max", 1000);

        MemoryStore s = null;
        if (enabled) {
            try {
                s = new SqliteMemoryStore();
            } catch (Throwable t) {
                // 驱动缺失/建表失败等：降级关闭，不打断启动
                log.error("LongTermMemory: store init failed, long-term memory disabled. ({})", t.toString());
            }
        }
        this.store = s;
        log.info("LongTermMemory: enabled={}, retrieval={}, reflection={}, dedup={}(th={}), capacity={}, storeReady={}",
                enabled, retrievalEnabled, reflectionEnabled, dedupEnabled, dedupThreshold,
                capacityMax > 0 ? capacityMax : "unlimited", store != null);
    }

    public static LongTermMemory get() {
        if (instance == null) {
            synchronized (LongTermMemory.class) {
                if (instance == null) {
                    instance = new LongTermMemory();
                }
            }
        }
        return instance;
    }

    /**
     * 记忆功能是否真正可用（总开关开启且存储就绪）
     */
    public boolean isActive() {
        return enabled && store != null;
    }

    public MemoryStore getStore() {
        return store;
    }

    // ---------------- 写入 ----------------

    public String remember(Memory memory) {
        if (!isActive() || memory == null
                || memory.getContent() == null || memory.getContent().isBlank()) {
            return null;
        }
        try {
            Memory target = memory;
            if (dedupEnabled) {
                Memory dup = findDuplicate(memory);
                if (dup != null) {
                    target = merge(dup, memory);
                    log.info("LongTermMemory: dedup hit, update existing id={} instead of insert", dup.getId());
                }
            }
            store.save(target);
            evictIfOverCapacity();
            return target.getId();
        } catch (Throwable t) {
            log.error("LongTermMemory: remember failed", t);
            return null;
        }
    }

    /**
     * 在同作用域、同分类的既有记忆里找近似重复项：先用检索拉候选，再逐一算相似度，
     * 取超过阈值的最高分者；找不到或异常返回 null（即按新记忆插入）。
     */
    private Memory findDuplicate(Memory incoming) {
        try {
            List<Memory> candidates = store.search(buildDedupQuery(incoming), dedupCandidates, incoming.getScope());
            Memory best = null;
            double bestScore = 0d;
            for (Memory c : candidates) {
                if (!sameCategory(c, incoming)) {
                    continue;
                }
                if (incoming.getId() != null && incoming.getId().equals(c.getId())) {
                    continue;
                }
                double score = MemorySimilarity.similarity(incoming, c);
                if (score > bestScore) {
                    bestScore = score;
                    best = c;
                }
            }
            return bestScore >= dedupThreshold ? best : null;
        } catch (Throwable t) {
            log.warn("LongTermMemory: findDuplicate failed, treat as new. ({})", t.toString());
            return null;
        }
    }

    /**
     * 合并新旧记忆：沿用旧记忆的 id 与 createdAt（save 会覆盖同 id），正文/标题以新值为准，
     * 关键词取并集，来源会话优先用新值。
     */
    private Memory merge(Memory old, Memory incoming) {
        Memory merged = new Memory();
        merged.setId(old.getId());
        merged.setCreatedAt(old.getCreatedAt());
        merged.setContent(incoming.getContent());
        merged.setTitle(incoming.getTitle() != null && !incoming.getTitle().isBlank()
                ? incoming.getTitle() : old.getTitle());
        merged.setCategory(incoming.getCategory() != null ? incoming.getCategory() : old.getCategory());
        merged.setScope(old.getScope() != null ? old.getScope() : incoming.getScope());
        merged.setKeywords(unionKeywords(old.getKeywords(), incoming.getKeywords()));
        merged.setSourceSessionId(incoming.getSourceSessionId() != null
                ? incoming.getSourceSessionId() : old.getSourceSessionId());
        return merged;
    }

    private static List<String> unionKeywords(List<String> a, List<String> b) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (List<String> src : List.of(a == null ? List.<String>of() : a, b == null ? List.<String>of() : b)) {
            for (String s : src) {
                if (s != null && !s.isBlank()) {
                    set.add(s.trim());
                }
            }
        }
        return new ArrayList<>(set);
    }

    private static boolean sameCategory(Memory a, Memory b) {
        MemoryCategory ca = a.getCategory() == null ? MemoryCategory.OTHER : a.getCategory();
        MemoryCategory cb = b.getCategory() == null ? MemoryCategory.OTHER : b.getCategory();
        return ca == cb;
    }

    /**
     * 构造去重候选检索词：标题 + 关键词 + 正文前若干字，尽量命中相似条目
     */
    private static String buildDedupQuery(Memory m) {
        StringBuilder sb = new StringBuilder();
        if (m.getTitle() != null) {
            sb.append(m.getTitle()).append(' ');
        }
        if (m.getKeywords() != null && !m.getKeywords().isEmpty()) {
            sb.append(String.join(" ", m.getKeywords())).append(' ');
        }
        String content = m.getContent() == null ? "" : m.getContent().trim();
        sb.append(content.length() > 60 ? content.substring(0, 60) : content);
        return sb.toString().trim();
    }

    /**
     * 容量淘汰：超过上限时按更新时间从旧到新删除多余条目（listRecent 为最新在前，尾部即最旧）
     */
    private void evictIfOverCapacity() {
        if (capacityMax <= 0) {
            return;
        }
        try {
            int total = store.count();
            if (total <= capacityMax) {
                return;
            }
            List<Memory> all = store.listRecent(total);
            int removed = 0;
            for (int i = capacityMax; i < all.size(); i++) {
                store.deleteById(all.get(i).getId());
                removed++;
            }
            log.info("LongTermMemory: evicted {} oldest memories (capacity={})", removed, capacityMax);
        } catch (Throwable t) {
            log.error("LongTermMemory: evict failed", t);
        }
    }

    public void rememberAll(List<Memory> memories) {
        if (!isActive() || memories == null) {
            return;
        }
        for (Memory m : memories) {
            remember(m);
        }
    }

    // ---------------- 检索与注入 ----------------

    /**
     * 按 query 召回相关记忆（作用域不限），失败或无结果返回空列表
     */
    public List<Memory> recall(String query) {
        return recall(query, null, topK);
    }

    /**
     * 按 query + 作用域召回，limit<=0 时回退默认 topK；scope 为 null 不限作用域
     */
    public List<Memory> recall(String query, MemoryScope scope, int limit) {
        if (!isActive() || !retrievalEnabled || query == null || query.isBlank()) {
            return Collections.emptyList();
        }
        int k = limit > 0 ? limit : topK;
        try {
            return store.search(query, k, scope);
        } catch (Throwable t) {
            log.error("LongTermMemory: recall failed, query={}", query, t);
            return Collections.emptyList();
        }
    }

    /**
     * 召回并格式化为可注入 prompt 的文本块；无结果返回空串
     */
    public String recallBlock(String query) {
        return recallBlock(query, null, topK);
    }

    public String recallBlock(String query, MemoryScope scope, int limit) {
        List<Memory> hits = recall(query, scope, limit);
        return hits.isEmpty() ? "" : format(hits);
    }

    // ---------------- 遗忘 ----------------

    /**
     * 按 id 删除一条记忆，成功返回 true
     */
    public boolean forget(String id) {
        if (!isActive() || id == null || id.isBlank()) {
            return false;
        }
        try {
            store.deleteById(id);
            return true;
        } catch (Throwable t) {
            log.error("LongTermMemory: forget failed, id={}", id, t);
            return false;
        }
    }

    /**
     * 按检索词删除命中的记忆（scope 为 null 不限），返回实际删除条数
     */
    public int forgetByQuery(String query, MemoryScope scope, int limit) {
        List<Memory> hits = recall(query, scope, limit);
        int removed = 0;
        for (Memory m : hits) {
            if (forget(m.getId())) {
                removed++;
            }
        }
        return removed;
    }

    /**
     * 把召回的记忆合并进「本轮要发给模型的消息副本」：
     * 直接拼到首个 system 消息末尾（保持全局仅一条 system，规避 Responses API 的多消息顺序问题），
     * 只改副本、不动 AgentState 的事件真相源。
     *
     * @param callContext 本轮 model.call 的消息列表（会被就地修改）
     * @param query       召回查询（通常是用户本轮输入）
     */
    public void injectRecall(List<MessageItem> callContext, String query) {
        String block = recallBlock(query);
        if (block.isEmpty() || callContext == null) {
            return;
        }
        if (!callContext.isEmpty() && callContext.get(0) instanceof SystemMessageItem base) {
            callContext.set(0, new SystemMessageItem(base.getContent() + "\n\n" + block));
        } else {
            callContext.add(0, new SystemMessageItem(block));
        }
    }

    // ---------------- 反思抽取 ----------------

    /**
     * run 结束后把本轮对话蒸馏成长期记忆并落库；未开启或无 Summarizer 时直接返回
     */
    public void reflectAndStore(String sessionId, List<MessageItem> transcript, Summarizer summarizer) {
        if (!isActive() || !reflectionEnabled || summarizer == null) {
            return;
        }
        try {
            List<Memory> memories = new MemoryExtractor(summarizer).extract(sessionId, transcript);
            rememberAll(memories);
        } catch (Throwable t) {
            log.error("LongTermMemory: reflectAndStore failed, sessionId={}", sessionId, t);
        }
    }

    // ---------------- 展示格式 ----------------

    public String format(List<Memory> hits) {
        StringBuilder sb = new StringBuilder();
        sb.append("===== 相关长期记忆 (top ").append(hits.size()).append(") =====\n");
        int i = 1;
        for (Memory m : hits) {
            sb.append(i++).append(". [").append(m.getCategory()).append("|").append(m.getScope()).append("] ");
            if (m.getTitle() != null && !m.getTitle().isBlank()) {
                sb.append(m.getTitle()).append("\n");
            } else {
                sb.append("\n");
            }
            sb.append(m.getContent().strip()).append("\n");
            if (m.getKeywords() != null && !m.getKeywords().isEmpty()) {
                sb.append("关键词: ").append(String.join(", ", m.getKeywords())).append("\n");
            }
            if (i <= hits.size()) {
                sb.append("\n");
            }
        }
        sb.append("==============================");
        return sb.toString();
    }
}
