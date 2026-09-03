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

    public CallToolFinishedEvent(String sessionId, String runId, String turnId, int round, MessageItem messageItem, String toolName, String output) {
        super(messageItem);
        this.sessionId = sessionId;
        this.runId = runId;
        this.turnId = turnId;
        this.round = round;
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
