package com.szh.tool.tools.git;

import com.fasterxml.jackson.databind.JsonNode;
import com.szh.tool.ToolDefinition;

import java.util.List;

/**
 * @author demussong
 * @describe 暂存改动，等价 {@code git add <paths>} 或 {@code git add -A}。
 * 结构化封装避免模型漏传路径或误用交互式 {@code git add -p}（本工具不支持分块交互）。
 * @date 2026/9/21
 */
public class GitAddTool extends AbstractGitWorkflowTool {

    public static final String CODE = "git_add";

    private static final ToolDefinition TOOL_DEFINITION = definition(
            CODE,
            "把改动加入暂存区（git add）。传 paths 暂存指定文件/目录（多个用空格分隔），或 all=true 暂存全部改动（git add -A）。二者至少给一个",
            "{\"type\":\"object\",\"properties\":{\"paths\":{\"type\":\"string\",\"description\":\"要暂存的路径，多个用空格分隔，如 src/main/java pom.xml\"},\"all\":{\"type\":\"boolean\",\"description\":\"true 时暂存全部改动，等价 git add -A\"}},\"required\":[]}");

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
        boolean all = json.path("all").asBoolean(false);
        String paths = text(json, "paths");
        if (!all && paths == null) {
            throw new IllegalArgumentException("必须提供 paths 或 all=true");
        }
        List<String> command = gitBase();
        command.add("add");
        if (all) {
            command.add("-A");
        }
        appendArgs(command, paths);
        return command;
    }
}
