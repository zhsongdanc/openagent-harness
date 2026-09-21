
package com.szh.agent.handler;

import com.szh.model.dto.ToolCall;
import lombok.Data;

import java.util.List;

/**
 * @author demussong
 * @describe 处理器执行结果，供 Runtime 判断是否继续循环、提取最终回答、收集待并行执行的工具调用
 * @date 2026/8/31
 */
@Data
public class HandleResult {

    private boolean toolCallExecuted;

    private String messageContent;

    /**
     * 本输出项收集到的工具调用（function_call 处理器返回），
     * 由运行时收齐一轮内全部调用后交给 ParallelToolExecutor 批量并发执行
     */
    private List<ToolCall> collectedCalls;

    public static HandleResult toolCall() {
        HandleResult result = new HandleResult();
        result.setToolCallExecuted(true);
        return result;
    }

    /**
     * 携带待执行调用的工具结果：标记本轮发生了工具调用，并把调用交给运行时批量执行
     */
    public static HandleResult toolCall(ToolCall call) {
        HandleResult result = new HandleResult();
        result.setToolCallExecuted(true);
        result.setCollectedCalls(List.of(call));
        return result;
    }

    public static HandleResult message(String content) {
        HandleResult result = new HandleResult();
        result.setMessageContent(content);
        return result;
    }

    public static HandleResult none() {
        return new HandleResult();
    }
}
