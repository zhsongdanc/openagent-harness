package com.szh.tool.tools.git;

import com.fasterxml.jackson.databind.JsonNode;
import com.szh.tool.ToolDefinition;
import com.szh.utils.ConfigUtil;

import java.util.List;
import java.util.Map;

/**
 * @author demussong
 * @describe 提交暂存区，等价 {@code git commit -m <message>}。这是“无法提交代码”问题的正面修复：
 * <ol>
 *   <li>强制要求 message 并以 {@code -m} 传入，杜绝裸 {@code git commit} 拉起编辑器阻塞子进程；</li>
 *   <li>注入 {@code GIT_EDITOR=true} 双保险，任何隐式编辑场景都立即返回而非挂起；</li>
 *   <li>仓库缺少 user.name/user.email 时 git 会直接拒绝提交，开启 {@code git.commit.autoIdentity}
 *       可用配置里的身份兜底注入（{@code -c user.name=.. -c user.email=..}），让无人值守场景也能提交。</li>
 * </ol>
 * @date 2026/9/21
 */
public class GitCommitTool extends AbstractGitWorkflowTool {

    public static final String CODE = "git_commit";

    private static final ToolDefinition TOOL_DEFINITION = definition(
            CODE,
            "提交暂存区改动（git commit -m）。message 必填；all=true 时自动带上已跟踪文件的改动(-a)；amend=true 修改上一次提交。提交前请确保已 git_add",
            "{\"type\":\"object\",\"properties\":{\"message\":{\"type\":\"string\",\"description\":\"提交信息，必填\"},\"all\":{\"type\":\"boolean\",\"description\":\"true 时等价 -a，自动暂存并提交已跟踪文件的改动\"},\"amend\":{\"type\":\"boolean\",\"description\":\"true 时修改上一次提交（--amend）\"}},\"required\":[\"message\"]}");

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
        String message = requireText(json, "message");
        boolean all = json.path("all").asBoolean(false);
        boolean amend = json.path("amend").asBoolean(false);

        // -c 身份兜底必须作为 git 顶层选项排在子命令之前，故单独构建而非直接用 gitBase()
        List<String> command = gitBase();
        if (ConfigUtil.getBoolean("git.commit.autoIdentity", false)) {
            String name = ConfigUtil.get("git.commit.userName", "openagent");
            String email = ConfigUtil.get("git.commit.email", "openagent@localhost");
            // 顶层选项顺序无强约束，但需都在 commit 之前；插到 --no-pager 之后
            command.add("-c");
            command.add("user.name=" + name);
            command.add("-c");
            command.add("user.email=" + email);
        }
        command.add("commit");
        command.add("-m");
        command.add(message);
        if (all) {
            command.add("-a");
        }
        if (amend) {
            command.add("--amend");
        }
        return command;
    }

    /**
     * 在基类关闭终端提示之外，再显式把编辑器指向 true（空操作立即成功），
     * 覆盖 --amend 等可能隐式拉起编辑器的路径
     */
    @Override
    protected Map<String, String> environment() {
        return Map.of("GIT_TERMINAL_PROMPT", "0", "GIT_EDITOR", "true");
    }
}
