package com.szh.tool.tools.shell;

import com.fasterxml.jackson.databind.JsonNode;
import com.szh.tool.ToolDefinition;

import java.util.ArrayList;
import java.util.List;

/**
 * @author demussong
 * @describe 读取文件末尾若干行，等价 shell：tail -n [行数] [文件路径]
 * @date 2026/9/3 10:20
 */
public class TailTool extends ShellCommandTool {

    public static final String CODE = "tail";

    /**
     * 默认行数比 tail 原生的 10 行大一些，日志尾部通常信息量更集中
     */
    private static final int DEFAULT_LINES = 50;

    private static final ToolDefinition TOOL_DEFINITION = definition(
            CODE,
            "输出文件末尾若干行，等价 shell 命令 tail -n 行数 文件路径。常用于看日志尾部，不支持 -f 跟踪（会一直阻塞直到超时）",
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
        command.add(String.valueOf(integer(json, "lines", DEFAULT_LINES)));
        command.add(requireText(json, "path"));
        return command;
    }
}
