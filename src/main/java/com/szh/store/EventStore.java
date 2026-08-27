package com.szh.store;

import com.szh.event.Event;

/**
 * @author demussong
 * @describe
 * @date 2026/8/27 17:47
 */
public interface EventStore {

    public void appendEvent(Event event);

    public StoreEnum getStoreType();
}
