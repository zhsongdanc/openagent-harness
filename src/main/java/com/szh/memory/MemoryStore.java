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
     * 全文检索召回并按作用域过滤；scope 为 null 时不限作用域。
     */
    List<Memory> search(String query, int topK, MemoryScope scope);

    /**
     * 按更新时间倒序列出最近若干条（无检索词时的兜底召回）
     */
    List<Memory> listRecent(int limit);

    /**
     * 记忆总条数
     */
    int count();
}
