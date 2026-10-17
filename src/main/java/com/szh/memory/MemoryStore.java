package com.szh.memory;

import java.util.List;

/**
 * 长期记忆存储抽象：把「记忆怎么存、怎么检索」与上层解耦，
 * 便于将来替换实现（内存 / SQLite FTS5 / 向量库）而不动调用方。
 *
 * @author demussong
 * @date 2026/9/21
 */
public interface MemoryStore {

    /**
     * 保存或更新一条记忆（以 id 为逻辑键，存在则覆盖）
     */
    void save(Memory memory);

    /**
     * 按 id 删除
     */
    void deleteById(String id);

    /**
     * 按 id 查询，不存在返回 null
     */
    Memory findById(String id);

    /**
     * 全文检索召回，按相关度（BM25）从高到低返回至多 topK 条。
     *
     * @param query 查询文本（自然语言/关键词均可）
     * @param topK  返回条数上限
     */
    default List<Memory> search(String query, int topK) {
        return search(query, topK, null);
    }

    /**
     * 全文检索召回并按作用域过滤；scope 为 null 时不限作用域（仍受项目可见性约束，见 4 参重载）。
     */
    default List<Memory> search(String query, int topK, MemoryScope scope) {
        return search(query, topK, scope, null);
    }

    /**
     * 全文检索召回并按作用域与项目可见性过滤；不限会话（兼容旧调用方/跨会话管理操作）。
     */
    default List<Memory> search(String query, int topK, MemoryScope scope, String projectKey) {
        return search(query, topK, scope, projectKey, null);
    }

    /**
     * 全文检索召回，按作用域、项目与会话可见性过滤。
     * <p>项目可见性：{@code projectKey} 为空时不限项目；否则 GLOBAL 天然跨项目可见，
     * PROJECT/SESSION 仅当 {@code project_key} 等于当前项目或为空串（历史遗留）时可见。
     * <p>会话隔离：{@code sessionId} 非空时，SESSION 作用域的记忆仅当
     * {@code source_session_id} 等于当前会话或为空串（历史遗留）时可见，
     * 其他会话的 SESSION 记忆不得泄入；GLOBAL/PROJECT 不受影响。
     *
     * @param query      查询文本（自然语言/关键词均可）
     * @param topK       返回条数上限
     * @param scope      作用域过滤，null 表示不限
     * @param projectKey 当前项目标识（规范化真实路径），null/空 表示不限项目
     * @param sessionId  当前会话 id，null/空 表示不做会话隔离；非空时仅本会话的 SESSION 记忆可见
     */
    List<Memory> search(String query, int topK, MemoryScope scope, String projectKey, String sessionId);

    /**
     * 按更新时间倒序列出最近若干条（无检索词时的兜底召回）
     */
    List<Memory> listRecent(int limit);
    
    /**
     * 按分类（可选限定作用域）列出最近的记忆，按更新时间倒序。
     * <p>供第三阶段「核心画像」常驻聚合：把全部 {@link MemoryCategory#PROFILE} 行拼成一小段
     * 固定注入 system prompt 的文本，不依赖当前问题的关键词召回。category 为 null 不限分类。
     *
     * @param category 分类过滤，null 不限
     * @param scope    作用域过滤，null 不限
     * @param limit    条数上限
     */
    List<Memory> listByCategory(MemoryCategory category, MemoryScope scope, int limit);

    /**
     * 记忆总条数
     */
    int count();
}
