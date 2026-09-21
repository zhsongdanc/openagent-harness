package com.szh.store;

import com.szh.event.Event;

import java.util.ArrayList;
import java.util.List;

/**
 * @author demussong
 * @describe
 * @date 2026/8/27 17:49
 */
public class MemoryEventStore implements EventStore{

    // 进程内、非持久：以实例为作用域，避免 static 共享导致的跨会话/跨测试泄漏
    private final List<Event> events = new ArrayList<>();

    @Override
    public void appendEvent(Event event) {
        events.add(event);
    }

    @Override
    public StoreEnum getStoreType() {
        return StoreEnum.MEMORY;
    }

    @Override
    public List<Event> getEvents(String sessionId) {
        return events.stream().filter(event -> event.getSessionId().equals(sessionId)).toList();
    }
}
