package com.szh.event;

import com.szh.context.dto.MessageItem;

import java.util.HashMap;
import java.util.Map;

/**
 * @author demussong
 * @describe
 * @date 2026/8/27 14:27
 */
public class CallToolStartedEvent extends Event {

    private String toolName;
    private String parameters;

    public CallToolStartedEvent(String sessionId, String runId, String turnId, int round, String toolName, String parameters) {
        super();
        this.sessionId = sessionId;
        this.runId = runId;
        this.turnId = turnId;
        this.round = round;

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

    @Override
    public Object payloadData() {
        Map<String, String> data = new HashMap<>();
        data.put("toolName", toolName);
        data.put("parameters", parameters);
        return data;
    }
}
