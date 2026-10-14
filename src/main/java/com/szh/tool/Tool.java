package com.szh.tool;

import java.util.List;

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

    /**
     * 该工具依赖的外部可执行文件（使用前提之一）。rebuild 时若 PATH 中缺任一，则本工具不注册，
     * 避免把本机不具备的命令暴露给模型诱发无谓试错（如裸 {@code git}/{@code mvn}）。默认空表示无外部命令前提。
     */
    default List<String> requiredBinaries() {
        return List.of();
    }
}
