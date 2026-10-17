package com.szh.memory;

import java.util.List;

/**
 * 活动目标存储抽象（第三阶段）：把「目标怎么存、按状态怎么查」与上层解耦。
 * <p>与 {@link MemoryStore}（知识库）/{@link CandidateStore}（候选层）并列，是 memory.db 里的
 * 第三张普通表 {@code memory_goals}。方法允许抛异常，由 {@link LongTermMemory} 统一捕获静默降级。
 *
 * @author demussong
 * @date 2026-10-16
 */
public interface GoalStore {

    /** 保存或更新一条目标（以 id 为逻辑键，upsert） */
    void save(MemoryGoal goal);

    /** 按 id 删除 */
    void deleteById(String id);

    /** 按 id 查询，不存在返回 null */
    MemoryGoal findById(String id);

    /**
     * 列出进行中的目标（常驻注入用），按更新时间倒序。
     * 项目可见：{@code projectKey} 为空不限；否则仅本项目的目标或空键遗留目标可见。
     */
    List<MemoryGoal> listActive(String projectKey, int limit);

    /**
     * 列出最近的目标（含终态，供 /memory 观测），按更新时间倒序；项目可见性同上。
     */
    List<MemoryGoal> listRecent(String projectKey, int limit);

    /** 目标总数 */
    int count();
}
