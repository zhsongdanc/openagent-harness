
package com.szh.agent.handler;

import com.szh.agent.AgentState;
import com.szh.tool.ToolRegistry;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * @author demussong
 * @describe 处理器执行上下文
 * @date 2026/8/31
 */
@Data
@AllArgsConstructor
public class HandleContext {

    private AgentState agentState;

    private ToolRegistry toolRegistry;

    private String sessionId;

    private String runId;

    private String turnId;

    /**
     * 当前轮次序号，每轮循环开始前由运行时刷新，处理器据此给事件打轮次标记
     */
    private int round;
}
