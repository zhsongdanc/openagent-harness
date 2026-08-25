package com.szh.model.dto;

/**
 * @author demussong
 * @describe
 * @date 2026/8/25 18:20
 */
public enum ActionEnum {
    FINAL_ANSWER("final_answer"),
    TOOL_CALL("tool_call"),
    ;

    private final String action;

    ActionEnum(String action) {
        this.action = action;
    }

    public String getAction() {
        return action;
    }
}
