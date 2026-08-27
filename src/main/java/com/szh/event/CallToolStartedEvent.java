package com.szh.event;

import com.szh.context.dto.MessageItem;

/**
 * @author demussong
 * @describe
 * @date 2026/8/27 14:27
 */
public class CallToolStartedEvent extends Event {

    private String toolName;
    private String parameters;

    public CallToolStartedEvent(String toolName, String parameters) {
        super();
        this.toolName = toolName;
        this.parameters = parameters;
    }

    public String getToolName() {
        return toolName;
    }

    public String getParameters() {
        return parameters;
    }

    @Override
    public EventEnum getType() {
        return EventEnum.CALL_TOOL_STARTED;
    }
}
