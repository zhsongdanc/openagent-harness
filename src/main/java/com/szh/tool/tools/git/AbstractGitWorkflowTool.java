package com.szh.tool.tools.git;

import com.szh.tool.ToolContext;
import com.szh.tool.tools.shell.ShellCommandTool;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * @author demussong
 * @describe git 工作流工具基类：把 add/commit/push/status 这类高频操作封装成参数结构化的独立工具，
 * 模型不再需要拼裸命令，也规避了裸 {@code git commit}（无 -m）弹出编辑器阻塞、凭据交互挂死等
 * “无法提交代码”的常见坑。
 * <p>
 * 统一约定：
 * 1. 命令前缀固定 {@code git --no-pager}，避免个别环境配置了 core.pager 导致分页阻塞；
 * 2. 注入 {@code GIT_TERMINAL_PROMPT=0}，网络类子命令（push）缺凭据时直接失败返回而非等待 stdin；
 * 3. 命令数组仍走 {@link ShellCommandTool} 的权限闸门与 OS 沙箱，写操作是否放行由安全层裁决。
 * <p>
 * 仓库前置闸门：执行任何 git 子命令前，先在 Java 侧从工作区向上遍历查找 {@code .git}
 * （目录，或 submodule/worktree 指向别处的 {@code .git} 文件）。找不到即判定「非 git 仓库」并短路返回
 * 结构化提示，不进子进程。这是为了根治「模型不确认仓库是否存在就脑补仓库状态、产生幻觉」——
 * 用一次零副作用的文件系统 stat 换取确定性事实，比事后让模型解读 {@code fatal: not a git repository} 更可靠，
 * 也契合本项目「框架级硬闸门 > 靠 prompt 求模型」的一贯风格。
 * @date 2026/9/21
 */
@Slf4j
public abstract class AbstractGitWorkflowTool extends ShellCommandTool {

    /**
     * 先做仓库存在性前置判定，非 git 仓库直接短路返回可操作提示；是仓库才交给父类走安全闸门 + 沙箱执行。
     */
    @Override
    public String execute(ToolContext toolContext) {
        String workspace = toolContext.getWorkspace();
        if (workspace == null || workspace.isBlank()) {
            workspace = System.getProperty("user.dir");
        }
        if (findGitDir(workspace) == null) {
            log.warn("git tool skipped, not a git repository: tool={}, workspace={}", getCode(), workspace);
            return getCode() + " 已跳过：当前工作区 " + workspace + " 不是 git 仓库（从该目录向上未找到 .git 目录/文件）。"
                    + "请不要假设仓库状态；如确需版本控制，先确认工作目录是否正确，或用 git init 初始化后重试。";
        }
        return super.execute(toolContext);
    }

    /**
     * 从给定目录逐级向上查找 {@code .git}，命中即返回该文件/目录，直到文件系统根仍未找到则返回 null。
     * 只读、零子进程：{@code .git} 既可能是普通仓库的目录，也可能是 submodule/worktree 指向别处的文件，
     * 两种都算「在仓库内」，故只判 exists() 不判类型。
     */
    private static File findGitDir(String workspace) {
        File start = new File(workspace);
        // 工作区路径本身不存在时无从判定，直接视为非仓库
        if (!start.isDirectory()) {
            return null;
        }
        File cursor = start.getAbsoluteFile();
        while (cursor != null) {
            File git = new File(cursor, ".git");
            if (git.exists()) {
                return git;
            }
            cursor = cursor.getParentFile();
        }
        return null;
    }

    /**
     * git 工作流工具依赖本机 {@code git} 命令：PATH 中不存在时 registry 不注册本组工具，
     * 避免把不可用命令暴露给模型（与裸 {@code GitTool} 同一前提）。
     */
    @Override
    public List<String> requiredBinaries() {
        return List.of("git");
    }

    /**
     * 起手式：git --no-pager，子类在此基础上追加子命令与参数
     */
    protected List<String> gitBase() {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("--no-pager");
        return command;
    }

    /**
     * 关掉 git 的终端交互提示：无凭据/需确认时立即失败，避免子进程挂在 stdin 上直到本类超时
     */
    @Override
    protected Map<String, String> environment() {
        return Map.of("GIT_TERMINAL_PROMPT", "0");
    }
}
