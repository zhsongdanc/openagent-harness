package com.szh.tool.tools.shell;

import com.szh.tool.ToolDefinition;

import java.util.List;

/**
 * @author demussong
 * @describe 打印当前工作目录，等价 shell：pwd
 * @date 2026/9/3 10:20
 */
public class PwdTool extends ShellCommandTool {

    public static final String CODE = "pwd";

    private static final ToolDefinition TOOL_DEFINITION = definition(
            CODE,
            "打印 agent 当前工作目录（即项目工作区根路径），无参数。用于确认后续文件操作的基准路径",
            "{\"type\":\"object\",\"properties\":{},\"required\":[]}");

    @Override
    public String getCode() {
        return CODE;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return TOOL_DEFINITION;
    }

    @Override
    protected List<String> buildCommand(String args) {
        return List.of(CODE);
    }
}
