package com.szh.memory;

import com.szh.context.compaction.Summarizer;
import com.szh.context.dto.MessageItem;
import com.szh.context.dto.SystemMessageItem;
import com.szh.utils.ConfigUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
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
 *   <li>{@link #remember(Memory)} / {@link #rememberAll(List)}：写入记忆；</li>
 *   <li>{@link #injectRecall(List, String)}：按 query 召回并合并进本轮 system prompt；</li>
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
    /** 存储初始化失败时为 null，所有操作自动变为 no-op */
    private final MemoryStore store;

    private LongTermMemory() {
        this.enabled = ConfigUtil.getBoolean("memory.enabled", true);
        this.retrievalEnabled = ConfigUtil.getBoolean("memory.retrieval.enabled", true);
        this.reflectionEnabled = ConfigUtil.getBoolean("memory.reflection.enabled", true);
        this.topK = ConfigUtil.getInt("memory.retrieval.topK", 5);

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
        log.info("LongTermMemory: enabled={}, retrieval={}, reflection={}, storeReady={}",
                enabled, retrievalEnabled, reflectionEnabled, store != null);
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

    public void remember(Memory memory) {
        if (!isActive() || memory == null) {
            return;
        }
        try {
            store.save(memory);
        } catch (Throwable t) {
            log.error("LongTermMemory: remember failed", t);
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
        if (!isActive() || !retrievalEnabled || query == null || query.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return store.search(query, topK);
        } catch (Throwable t) {
            log.error("LongTermMemory: recall failed, query={}", query, t);
            return Collections.emptyList();
        }
    }

    /**
     * 召回并格式化为可注入 prompt 的文本块；无结果返回空串
     */
    public String recallBlock(String query) {
        List<Memory> hits = recall(query);
        return hits.isEmpty() ? "" : format(hits);
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

    private String format(List<Memory> hits) {
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
