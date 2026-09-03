package com.szh.tool.tools.shell;

import com.fasterxml.jackson.databind.JsonNode;
import com.szh.tool.ToolDefinition;

import java.util.ArrayList;
import java.util.List;

/**
 * @author demussong
 * @describe 执行 git 子命令，等价 shell：git [子命令及参数]
 * @date 2026/9/3 10:20
 */
public class GitTool extends ShellCommandTool {

    public static final String CODE = "git";

    private static final ToolDefinition TOOL_DEFINITION = definition(
            CODE,
            "在工作区执行 git 子命令，如 status -sb、log --oneline -10、diff HEAD~1、show HEAD。固定带 --no-pager 避免分页阻塞；不区分只读与写操作，push、reset 等命令是否放行由使用方约束",
            "{\"type\":\"object\",\"properties\":{\"command\":{\"type\":\"string\",\"description\":\"git 子命令及其参数，如 status -sb\"}},\"required\":[\"command\"]}");

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
        JsonNode json = parseArgs(args);

        List<String> command = new ArrayList<>();
        command.add(CODE);
        // 输出被重定向到管道时 git 默认不分页，但个别环境配置了 core.pager，显式关掉更稳
        command.add("--no-pager");
        appendArgs(command, requireText(json, "command"));
        return command;
    }
}
