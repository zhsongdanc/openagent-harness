package com.szh.memory;

import lombok.Data;

/**
 * 记忆整理决策日志条目（memory_consolidation_log 的行模型）：append-only。
 * <p>SUPERSEDE/UPDATE 被替换或删除的旧记忆以 {@link #beforeSnapshot}（JSON）归档在此，
 * 这是「新事实取代旧事实但旧记录仍可追溯」的落点——不给 memory_fts 加状态列（FTS5 加列
 * 需整表重建迁移），追溯与第三阶段的可观测性都消费这张普通表。
 *
 * @author demussong
 * @date 2026-10-16
 */
@Data
public class ConsolidationLogEntry {

    /** 触发本次决策的候选 id */
    private String candidateId;

    /** 候选的内容幂等指纹（同指纹的重复注入也在此可查） */
    private String fingerprint;

    /** 决策动作 */
    private ConsolidationAction action;

    /** 决策后指向的正式记忆 id（DISCARD 为空） */
    private String memoryId;

    /** 变更前既有记忆的 JSON 快照（ADD/SKIP 无变更，为空） */
    private String beforeSnapshot;

    /** 决策说明：相似度、仲裁理由或丢弃原因 */
    private String note;

    private long createdAt;
}
