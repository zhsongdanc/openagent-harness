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

    public UserMessageEvent(MessageItem messageItem, String userInput) {
        super(messageItem);
        this.userInput = userInput;
    }

    @Override
    public EventEnum getType() {
        return EventEnum.USER_INPUT;
    }
}
