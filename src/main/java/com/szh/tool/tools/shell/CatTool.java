package com.szh.tool.tools.shell;

import com.fasterxml.jackson.databind.JsonNode;
import com.szh.tool.ToolDefinition;

import java.util.ArrayList;
import java.util.List;

/**
 * @author demussong
 * @describe 读取文件全部内容，等价 shell：cat [文件路径]
 * @date 2026/9/3 10:20
 */
public class CatTool extends ShellCommandTool {

    public static final String CODE = "cat";

    private static final ToolDefinition TOOL_DEFINITION = definition(
            CODE,
            "输出文本文件的完整内容，等价 shell 命令 cat。大文件请先用 head/tail 或 grep 定位，避免一次性读入过多内容",
            "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\",\"description\":\"文件路径，相对工作区根目录，如 pom.xml\"}},\"required\":[\"path\"]}");

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
        command.add(requireText(json, "path"));
        return command;
    }
}
