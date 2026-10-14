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
            "【适用前提】仅当任务确实涉及版本控制（查看/提交/推送/查历史）时才使用；纯脚本、数值计算、数据处理等与版本管理无关的任务无需调用本工具，不要因为它是内置工具就试探性运行。"
                    + "在工作区执行 git 子命令，如 status -sb、log --oneline -10、diff HEAD~1、show HEAD。固定带 --no-pager 避免分页阻塞；不区分只读与写操作，push、reset 等命令是否放行由使用方约束",
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
    public List<String> requiredBinaries() {
        return List.of(CODE);
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
