package com.szh.event;

/**
 * @author demussong
 * @describe
 * @date 2026/8/27 18:12
 */
public class RunStartedEvent extends Event {
    @Override
    public EventEnum getType() {
        return EventEnum.RUN_STARTED;
    }
}
