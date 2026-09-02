package com.szh.event;

import com.szh.context.dto.MessageItem;
import com.szh.utils.CommonUtils;

import java.time.Instant;

/**
 * @author demussong
 * @describe 事件基类
 * @date 2026/8/27 14:07
 */
public abstract class Event {

    private String id;

    private long timestamp;

    protected String sessionId;

    protected String runId;

    protected String turnId;

    protected MessageItem messageItem;

    public Event() {
        this.id = CommonUtils.generateId();
        this.timestamp = System.currentTimeMillis();
    }

    public abstract EventEnum getType();

    /**
     * 该事件要落库的业务数据，作为事件日志的唯一真相；默认无数据，子类按需覆盖
     */
    public Object payloadData() {
        return null;
    }

    public String getId() {
        return id;
    }

    public String getRunId() {
        return runId;
    }

    public String getTurnId() {
        return turnId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setId(String id) {
        this.id = id;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

}
