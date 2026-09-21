package com.szh.tool.tools.git;

import com.szh.tool.tools.shell.ShellCommandTool;

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
 * @date 2026/9/21
 */
public abstract class AbstractGitWorkflowTool extends ShellCommandTool {

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
