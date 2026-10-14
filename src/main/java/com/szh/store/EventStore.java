package com.szh.store;

import com.szh.event.Event;

import java.util.List;

/**
 * @author demussong
 * @describe
 * @date 2026/8/27 17:47
 */
public interface EventStore {

    public void appendEvent(Event event);

    public StoreEnum getStoreType();

    public List<Event> getEvents(String sessionId);

    /**
     * 判断某 session 是否已有持久化事件（用于断点恢复前的存在性检查）。
     * 默认基于 getEvents 判定；持久化引擎（如 FILE）可覆盖为更廉价的存在性检查。
     */
    default boolean exists(String sessionId) {
        List<Event> events = getEvents(sessionId);
        return events != null && !events.isEmpty();
    }

    /**
     * 截断事件日志：删除某 session 中轮次序号 {@code >= turnSeq} 的全部事件，
     * 供「回溯到历史时刻」撤销被回退轮次的落库记录，保证持久化引擎下续跑不会重放到旧事件。
     * 默认 no-op（不支持截断的实现仅重建内存上下文）；持久化引擎应覆盖实现。
     */
    default void truncateFromTurn(String sessionId, int turnSeq) {
    }
}
