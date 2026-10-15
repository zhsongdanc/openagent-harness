package com.szh.event;

/**
 * @author demussong
 * @describe
 * @date 2026/8/27 14:07
 */
public enum EventEnum {
    RUN_STARTED,
    USER_INPUT,
    CALL_MODEL_FINISHED,
    MODEL_REASONING,
    /**
     * 每轮模型调用的 token 用量（surface 事件，不进 modelContext，供 Trace 查看器/回放消费）
     */
    TOKEN_USAGE,
    CALL_TOOL_STARTED,
    CALL_TOOL_FINISHED,
    TODO_UPDATED,
    MODE_SWITCHED,
    VERIFICATION,
    CONTEXT_COMPACTED,
    RUN_COMPLETED,
    ;
}
