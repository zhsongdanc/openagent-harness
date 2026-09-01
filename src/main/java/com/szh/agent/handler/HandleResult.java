
package com.szh.agent.handler;

import lombok.Data;

/**
 * @author demussong
 * @describe 处理器执行结果，供 Runtime 判断是否继续循环、提取最终回答
 * @date 2026/8/31
 */
@Data
public class HandleResult {

    private boolean toolCallExecuted;

    private String messageContent;

    public static HandleResult toolCall() {
        HandleResult result = new HandleResult();
        result.setToolCallExecuted(true);
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
