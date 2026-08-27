package com.szh.event;

import java.time.Instant;

/**
 * @author demussong
 * @describe
 * @date 2026/8/27 14:16
 */
public class UserMessageEvent extends Event {
    private String userInput;

    public UserMessageEvent(String sessionId, String userInput) {
        super();
        this.sessionId = sessionId;
        this.userInput = userInput;
    }

    @Override
    public EventEnum getType() {
        return EventEnum.USER_INPUT;
    }
}
