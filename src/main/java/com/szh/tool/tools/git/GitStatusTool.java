package com.szh.tool.tools.git;

import com.fasterxml.jackson.databind.JsonNode;
import com.szh.tool.ToolDefinition;

import java.util.List;

/**
 * @author demussong
 * @describe 查看工作区改动状态，等价 {@code git status -sb}（短格式 + 分支跟踪信息）。
 * 提交流程的第一步，只读、不改动仓库，便于模型在 add/commit 前确认将要提交的改动范围。
 * @date 2026/9/21
 */
public class GitStatusTool extends AbstractGitWorkflowTool {

    public static final String CODE = "git_status";

    private static final ToolDefinition TOOL_DEFINITION = definition(
            CODE,
            "查看工作区改动状态（git status -sb，短格式含分支跟踪）。提交前先用它确认改动范围。可选 paths 限定到指定文件/目录",
            "{\"type\":\"object\",\"properties\":{\"paths\":{\"type\":\"string\",\"description\":\"可选，限定查看的路径，多个用空格分隔；缺省查看整个仓库\"}},\"required\":[]}");

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
        List<String> command = gitBase();
        command.add("status");
        command.add("-sb");
        appendArgs(command, text(json, "paths"));
        return command;
    }
}
