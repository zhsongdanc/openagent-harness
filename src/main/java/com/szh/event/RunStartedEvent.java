package com.szh.event;

import java.util.Map;

/**
 * @author demussong
 * @describe
 * @date 2026/8/27 18:12
 */
public class RunStartedEvent extends Event {

    public RunStartedEvent(String sessionId, String runId, String turnId) {
        super();
        this.sessionId = sessionId;
        this.runId = runId;
        this.turnId = turnId;
    }

    @Override
    public EventEnum getType() {
        return EventEnum.RUN_STARTED;
    }

    @Override
    public Object payloadData() {
        // 该事件本身没有额外业务数据，返回空对象让 payload 语义显式化
        return Map.of();
    }
}
