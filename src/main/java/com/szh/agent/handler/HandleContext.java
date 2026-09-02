
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
}
