package com.szh.tool.tools.meta;

/**
 * Agent 运行模式（对标 Claude Code 的 SwitchMode / plan 模式）。
 * <ul>
 *   <li>{@link #NORMAL}：默认模式，全部工具可用，边想边改；</li>
 *   <li>{@link #PLAN}：规划模式，收敛为只读工具子集 + 注入规划指令，让 agent 先出方案再执行。</li>
 * </ul>
 *
 * @author demussong
 * @date 2026/9/22
 */
public enum AgentMode {

    NORMAL,

    PLAN;

    /**
     * 宽松解析：plan/planning 归 PLAN；agent/default/normal 及未知归 NORMAL。
     */
    public static AgentMode from(String raw) {
        if (raw == null) {
            return NORMAL;
        }
        String v = raw.trim().toLowerCase();
        if (v.startsWith("plan")) {
            return PLAN;
        }
        return NORMAL;
    }

    public String label() {
        return this == PLAN ? "PLAN（规划模式，只读工具）" : "NORMAL（普通模式，全部工具）";
    }
}
