package com.szh.tool.tools.git;

import com.fasterxml.jackson.databind.JsonNode;
import com.szh.tool.ToolDefinition;

import java.util.List;

/**
 * @author demussong
 * @describe 推送到远端，等价 {@code git push [remote] [branch]}。写 + 联网操作，
 * 是否放行由权限层与沙箱裁决（git 已在 {@code shell.sandbox.network.tools} 联网豁免名单内）。
 * 缺省推 origin 当前分支；首次推送分支可用 setUpstream=true 建立跟踪（-u）。
 * @date 2026/9/21
 */
public class GitPushTool extends AbstractGitWorkflowTool {

    public static final String CODE = "git_push";

    private static final ToolDefinition TOOL_DEFINITION = definition(
            CODE,
            "推送本地提交到远端（git push）。remote 缺省 origin，branch 缺省当前分支；setUpstream=true 时用 -u 建立分支跟踪。这是联网写操作，可能触发权限确认",
            "{\"type\":\"object\",\"properties\":{\"remote\":{\"type\":\"string\",\"description\":\"远端名，缺省 origin\"},\"branch\":{\"type\":\"string\",\"description\":\"要推送的分支名，缺省当前分支\"},\"setUpstream\":{\"type\":\"boolean\",\"description\":\"true 时加 -u 建立上游跟踪\"}},\"required\":[]}");

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
        boolean setUpstream = json.path("setUpstream").asBoolean(false);

        List<String> command = gitBase();
        command.add("push");
        if (setUpstream) {
            command.add("-u");
        }
        appendArg(command, text(json, "remote"));
        appendArg(command, text(json, "branch"));
        return command;
    }
}
