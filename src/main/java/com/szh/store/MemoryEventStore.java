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

    private static final List<Event> events = new ArrayList<>();

    @Override
    public void appendEvent(Event event) {
        events.add(event);
    }

    @Override
    public StoreEnum getStoreType() {
        return StoreEnum.MEMORY;
    }

    @Override
    public List<Event> getEvents() {
        return events;
    }
}
