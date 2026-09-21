package com.szh.tool;

/**
 * @author demussong
 * @describe
 * @date 2026/8/28 11:46
 */
public interface Tool {

    public String getCode();

    public ToolDefinition getToolDefinition();

    public String execute(ToolContext toolContext);

    /**
     * 该工具的输出是否应直接内联回传上下文、豁免“落盘 + 引用存根”机制。
     * <p>
     * 元工具（如 read_tool_result 本身就是回读正文）必须返回 true，否则其输出会被再次落盘
     * 并替换成存根，导致模型永远读不到正文（无限套娃）。默认 false，即普通工具走落盘策略。
     */
    default boolean inlineResult() {
        return false;
    }
}
