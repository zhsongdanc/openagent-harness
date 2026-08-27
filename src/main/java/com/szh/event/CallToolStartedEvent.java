package com.szh.event;

/**
 * @author demussong
 * @describe
 * @date 2026/8/27 14:27
 */
public class CallToolStartedEvent extends Event {

    private String toolName;
    private String parameters;

    public CallToolStartedEvent(String sessionId, String toolName, String parameters) {
        super();
        this.sessionId = sessionId;
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
