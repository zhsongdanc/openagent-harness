package com.szh.tool.tools.shell;

import com.fasterxml.jackson.databind.JsonNode;
import com.szh.tool.ToolDefinition;

import java.util.ArrayList;
import java.util.List;

/**
 * @author demussong
 * @describe 按正则搜索文件内容，等价 shell：grep [选项] 模式 [路径]
 * @date 2026/9/3 10:20
 */
public class GrepTool extends ShellCommandTool {

    public static final String CODE = "grep";

    private static final ToolDefinition TOOL_DEFINITION = definition(
            CODE,
            "按正则表达式搜索文件内容并返回命中行（含文件名与行号），等价 shell 命令 grep。默认递归当前工作目录，可用 path 缩小范围、options 传 -i -A 3 --include=*.java 等",
            "{\"type\":\"object\",\"properties\":{\"pattern\":{\"type\":\"string\",\"description\":\"正则表达式，如 buildCommand\"},\"path\":{\"type\":\"string\",\"description\":\"搜索的文件或目录，默认当前工作目录\"},\"options\":{\"type\":\"string\",\"description\":\"grep 选项，多个选项用空格分隔，默认 -rn\"}},\"required\":[\"pattern\"]}");

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
        String path = text(json, "path");

        List<String> command = new ArrayList<>();
        command.add(CODE);
        appendOptions(command, text(json, "options"), "-rn");
        command.add(requireText(json, "pattern"));
        command.add(path == null ? "." : path);
        return command;
    }
}
