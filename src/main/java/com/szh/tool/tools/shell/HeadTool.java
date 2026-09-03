package com.szh.tool.tools.shell;

import com.fasterxml.jackson.databind.JsonNode;
import com.szh.tool.ToolDefinition;

import java.util.ArrayList;
import java.util.List;

/**
 * @author demussong
 * @describe 读取文件开头若干行，等价 shell：head -n [行数] [文件路径]
 * @date 2026/9/3 10:20
 */
public class HeadTool extends ShellCommandTool {

    public static final String CODE = "head";

    /**
     * 默认行数比 head 原生的 10 行大一些，让模型一次能拿到足够的上下文
     */
    private static final int DEFAULT_LINES = 50;

    private static final ToolDefinition TOOL_DEFINITION = definition(
            CODE,
            "输出文件开头若干行，等价 shell 命令 head -n 行数 文件路径。用于查看大文件的头部内容",
            "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\",\"description\":\"文件路径，相对工作区根目录\"},\"lines\":{\"type\":\"integer\",\"description\":\"读取的行数，默认 " + DEFAULT_LINES + "\"}},\"required\":[\"path\"]}");

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
        command.add("-n");
        command.add(String.valueOf(Math.max(1, integer(json, "lines", DEFAULT_LINES))));
        command.add(requireText(json, "path"));
        return command;
    }
}
