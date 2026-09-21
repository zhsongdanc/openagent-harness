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
}
