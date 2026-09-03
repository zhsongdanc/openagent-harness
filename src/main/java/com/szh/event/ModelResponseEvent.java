package com.szh.event;

import com.szh.context.dto.MessageItem;

/**
 * @author demussong
 * @describe
 * @date 2026/8/27 14:27
 */
public class ModelResponseEvent extends MessageEvent {

    public ModelResponseEvent(String sessionId, String runId, String turnId, int round, MessageItem messageItem) {
        super(messageItem);
        this.sessionId = sessionId;
        this.runId = runId;
        this.turnId = turnId;
        this.round = round;
    }


    @Override
    public EventEnum getType() {
        return EventEnum.CALL_MODEL_FINISHED;
    }
}
