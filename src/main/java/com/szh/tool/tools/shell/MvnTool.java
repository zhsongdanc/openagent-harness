package com.szh.tool.tools.shell;

import com.fasterxml.jackson.databind.JsonNode;
import com.szh.tool.ToolDefinition;

import java.util.ArrayList;
import java.util.List;

/**
 * @author demussong
 * @describe 执行 maven 构建，等价 shell：mvn [目标及参数]
 * @date 2026/9/3 10:20
 */
public class MvnTool extends ShellCommandTool {

    public static final String CODE = "mvn";

    private static final ToolDefinition TOOL_DEFINITION = definition(
            CODE,
            "在工作区执行 maven 构建，如 clean compile、test -Dtest=FooTest、package -DskipTests、dependency:tree。默认批处理模式 -B（无进度条、无交互）；构建耗时较长，超时会被中断；子进程继承当前环境变量，JAVA_HOME 与项目要求的 JDK 不一致时会编译失败",
            "{\"type\":\"object\",\"properties\":{\"command\":{\"type\":\"string\",\"description\":\"maven 目标及其参数，如 clean package -DskipTests\"},\"options\":{\"type\":\"string\",\"description\":\"附加的 mvn 全局选项，多个用空格分隔，默认 -B\"}},\"required\":[\"command\"]}");

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
        appendOptions(command, text(json, "options"), "-B");
        appendArgs(command, requireText(json, "command"));
        return command;
    }
}
