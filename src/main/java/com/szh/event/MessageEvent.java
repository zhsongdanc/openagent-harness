package com.szh.event;

import com.szh.context.dto.MessageItem;

/**
 * @author demussong
 * @describe
 * @date 2026/8/27 18:01
 */
public abstract class MessageEvent extends Event {
    private MessageItem messageItem;

    public MessageEvent(MessageItem messageItem) {
        super();
        this.messageItem = messageItem;
    }

    public MessageItem getMessageItem() {
        return messageItem;
    }
}
