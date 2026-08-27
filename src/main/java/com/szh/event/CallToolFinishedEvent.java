package com.szh.event;

import com.szh.context.dto.MessageItem;

/**
 * @author demussong
 * @describe
 * @date 2026/8/27 14:27
 */
public class CallToolFinishedEvent extends MessageEvent {
    private String toolName;
    private String output;

    public CallToolFinishedEvent(MessageItem messageItem, String sessionId, String toolName, String output) {
        super(messageItem);
        this.sessionId = sessionId;
        this.toolName = toolName;
        this.output = output;
    }

    public String getToolName() {
        return toolName;
    }

    public String getOutput() {
        return output;
    }

    @Override
    public EventEnum getType() {
        return EventEnum.CALL_TOOL_FINISHED;
    }
}
