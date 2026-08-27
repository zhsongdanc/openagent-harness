package com.szh.event;

import com.szh.context.dto.MessageItem;
import com.szh.utils.CommonUtils;

import java.time.Instant;

/**
 * @author demussong
 * @describe
 * @date 2026/8/27 14:07
 */
public abstract class Event {

    private String id;

    private Instant timestamp;

    protected String sessionId;

    protected String turnId;

    protected MessageItem messageItem;

    public Event() {
        this.id = CommonUtils.generateId();
        this.timestamp = Instant.now();
    }

    public abstract EventEnum getType();

    public String getTurnId() {
        return turnId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

}
