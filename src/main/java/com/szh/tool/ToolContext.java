package com.szh.tool;

import com.szh.agent.AgentState;
import com.szh.utils.ConfigUtil;
import lombok.Data;

/**
 * @author demussong
 * @describe
 * @date 2026/9/2 21:11
 */
@Data
public class ToolContext {

    private String sessionId;
    private String runId;
    private String workspace;
    private String args;

    /**
     * 当前轮次 ID 与序号：由运行时（ParallelToolExecutor）注入，供元工具落领域事件时打轮次标记，
     * 使 TodoUpdatedEvent/ModeSwitchedEvent 在 TraceReplay 里能正确归入对应 run/round。默认空/0。
     */
    private String turnId;
    private int round;

    /**
     * 运行期状态入口：仅由运行时经 ParallelToolExecutor 注入。元工具（todo_write / switch_mode）
     * 借它把领域事件写进事件日志（唯一真相源）。脱离运行时的单测直调时为 null，元工具会静默跳过落库。
     */
    private AgentState agentState;

    public ToolContext(String sessionId, String runId, String workspace, String args) {
        this.sessionId = sessionId;
        this.runId = runId;
        this.workspace = workspace != null ? workspace : ConfigUtil.get("project.workspace", System.getProperty("user.dir"));
        this.args = args;
    }

    public ToolContext(String args) {
        this.workspace = ConfigUtil.get("project.workspace", System.getProperty("user.dir"));
        this.args = args;
    }
}
