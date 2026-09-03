package com.szh.tool.tools.shell;

import com.fasterxml.jackson.databind.JsonNode;
import com.szh.tool.ToolDefinition;

import java.util.ArrayList;
import java.util.List;

/**
 * @author demussong
 * @describe 按名称查找文件，等价 shell：find [路径] [选项] -name [模式]
 * @date 2026/9/3 10:20
 */
public class FindTool extends ShellCommandTool {

    public static final String CODE = "find";

    private static final ToolDefinition TOOL_DEFINITION = definition(
            CODE,
            "按文件名模式查找文件路径，等价 shell 命令 find。name 为 glob 模式（如 *.java、ShellCommandTool），不传则列出全部；options 用于附加 -type f 等条件",
            "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\",\"description\":\"查找起始目录，默认当前工作目录\"},\"name\":{\"type\":\"string\",\"description\":\"文件名匹配模式，如 *.java，不做 shell 展开\"},\"options\":{\"type\":\"string\",\"description\":\"find 的其他参数，如 -type f，多个用空格分隔\"}},\"required\":[]}");

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
        String name = text(json, "name");
        String path = text(json, "path");

        List<String> command = new ArrayList<>();
        command.add(CODE);
        command.add(path == null ? "." : path);
        appendOptions(command, text(json, "options"), null);
        if (name != null) {
            command.add("-name");
            command.add(name);
        }
        return command;
    }
}
