
package com.szh.agent.handler;

import com.szh.agent.AgentState;
import com.szh.agent.LoopGuard;
import com.szh.tool.ToolRegistry;
import com.szh.tool.store.ToolResultStore;
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

    private String workspace;

    /**
     * session 级持久化的工具结果存储：由运行时在 run 开始时创建并复用，
     * 保证 resultId 在整个会话内单调递增，避免每次调用都覆盖同一文件。
     */
    private ToolResultStore toolResultStore;

    /**
     * run 级熔断器：由运行时创建并注入，处理器执行工具后回写调用结果，
     * 运行时据此判断是否因连续失败/重复调用而提前结束 run。
     */
    private LoopGuard loopGuard;
}
