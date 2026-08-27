package com.szh.event;

import com.szh.context.dto.MessageItem;

/**
 * @author demussong
 * @describe
 * @date 2026/8/27 14:27
 */
public class ModelResponseEvent extends MessageEvent {
    private String modelRes;

    public ModelResponseEvent(String sessionId, String turnId, MessageItem messageItem) {
        super(messageItem);
        this.sessionId = sessionId;
        this.turnId = turnId;
    }


    @Override
    public EventEnum getType() {
        return EventEnum.CALL_MODEL_FINISHED;
    }
}
