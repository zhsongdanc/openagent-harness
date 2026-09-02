package com.szh.event;

import com.szh.context.dto.MessageItem;

import java.time.Instant;

/**
 * @author demussong
 * @describe
 * @date 2026/8/27 14:16
 */
public class UserMessageEvent extends MessageEvent {
    private String userInput;

    public UserMessageEvent(String sessionId, String runId, String turnId, MessageItem messageItem, String userInput) {
        super(messageItem);
        this.sessionId = sessionId;
        this.runId = runId;
        this.turnId = turnId;
        this.userInput = userInput;
    }

    @Override
    public EventEnum getType() {
        return EventEnum.USER_INPUT;
    }
}
