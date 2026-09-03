package com.szh.event;

/**
 * @author demussong
 * @describe
 * @date 2026/8/27 14:26
 */

public class RunCompletedEvent extends Event {
    private String result;

    public RunCompletedEvent(String sessionId, String runId, String turnId, int round, String result) {
        super();
        this.sessionId = sessionId;
        this.runId = runId;
        this.turnId = turnId;
        this.round = round;
        this.result = result;
    }

    public String getResult() {
        return result;
    }

    @Override
    public EventEnum getType() {
        return EventEnum.RUN_COMPLETED;
    }

    @Override
    public Object payloadData() {
        return result;
    }
}
