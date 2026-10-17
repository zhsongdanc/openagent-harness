package com.szh.memory;

import lombok.Data;

/**
 * 活动目标（第三阶段）：一条「尚未完成、值得跨轮跟踪」的事项，独立存于 {@code memory_goals} 表。
 * <p>与普通 {@link Memory} 分表存放：目标有明确的状态机（{@link GoalStatus}）与完成时间，需要按
 * 「进行中」过滤常驻注入，语义上是待办而非知识，混进记忆库会污染召回。
 *
 * @author demussong
 * @date 2026-10-16
 */
@Data
public class MemoryGoal {

    /** 逻辑主键 */
    private String id;

    /** 简短标题（目标名） */
    private String title;

    /** 目标正文：要达成什么、当前进展、下一步 */
    private String content;

    private GoalStatus status = GoalStatus.ACTIVE;

    /** 项目归属（规范化真实路径）；空串表示跨项目/未归属，可见性判定同 Memory */
    private String projectKey;

    /** 登记来源会话 id，可为空 */
    private String sourceSessionId;

    private long createdAt;

    private long updatedAt;

    /** 进入终态（DONE/CANCELLED/SUPERSEDED）的时间戳，ACTIVE 期间为 0 */
    private long completedAt;
}
