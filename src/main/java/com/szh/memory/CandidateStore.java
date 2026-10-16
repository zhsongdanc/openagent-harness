package com.szh.memory;

import java.util.List;

/**
 * 记忆候选层存储抽象：候选队列 + 整理决策日志的读写。
 * <p>与 {@link MemoryStore}（正式记忆库）分离——候选是派生层的过渡数据，
 * 支持替换实现（内存/SQLite），上层 {@link MemoryConsolidator} 不感知存储细节。
 * 所有方法允许抛异常，由 {@link LongTermMemory} 统一捕获静默降级。
 *
 * @author demussong
 * @date 2026-10-16
 */
public interface CandidateStore {

    /**
     * 按指纹幂等插入候选：fingerprint 已存在则什么都不做。
     *
     * @return true 表示新插入；false 表示同指纹候选已存在（重复注入，应跳过整理）
     */
    boolean insertIfAbsent(MemoryCandidate candidate);

    /** 按指纹查既有候选（显式写入的幂等回执用），不存在返回 null */
    MemoryCandidate findByFingerprint(String fingerprint);

    /** 按 id upsert 候选（整理完成后回写终态字段） */
    void save(MemoryCandidate candidate);

    /** 按创建时间升序列出待处理候选（进程残留兜底 drain 用） */
    List<MemoryCandidate> listPending(int limit);

    /** 追加一条整理决策日志 */
    void appendLog(ConsolidationLogEntry entry);

    /** 最近 limit 条决策日志（新在前），供排查与可观测性消费 */
    List<ConsolidationLogEntry> recentLogs(int limit);

    /**
     * 清理已处理（非 PENDING）候选，仅保留最近 keepRecent 条。
     *
     * @return 实际删除条数
     */
    int pruneProcessed(int keepRecent);
}
