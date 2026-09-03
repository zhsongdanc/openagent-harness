package com.szh.tool.tools.shell;

import com.fasterxml.jackson.databind.JsonNode;
import com.szh.tool.ToolDefinition;

import java.util.ArrayList;
import java.util.List;

/**
 * @author demussong
 * @describe 列出目录内容，等价 shell：ls [选项] [路径]
 * @date 2026/9/2 21:38
 */
public class ListFileTool extends ShellCommandTool {

    public static final String CODE = "ls";

    private static final ToolDefinition TOOL_DEFINITION = definition(
            CODE,
            "列出目录下的文件与子目录，等价 shell 命令 ls。path 为工作区内的相对或绝对路径，不传则列出当前工作目录",
            "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\",\"description\":\"要列出的目录路径，默认当前工作目录\"},\"options\":{\"type\":\"string\",\"description\":\"ls 选项，多个选项用空格分隔，默认 -la\"}},\"required\":[]}");

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
        appendOptions(command, text(json, "options"), "-la");
        appendArg(command, text(json, "path"));
        return command;
    }
}
