package com.szh.tool.security;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * @author demussong
 * @describe 命令风险分类器：把一条待执行的命令数组归类为只读/写入/联网/危险，供 {@link PermissionController} 决策。
 * <p>
 * 分类以「可执行文件名 + 关键参数」为依据，采取保守策略：
 * <ul>
 *   <li>明确的只读命令（cat/ls/grep/find/head/tail/pwd 等）→ 只读；</li>
 *   <li>破坏性命令（rm/mkfs/dd/shutdown 等）→ 危险，默认拦截或要求确认；</li>
 *   <li>git 按子命令细分：status/log/diff 只读，push/pull/fetch/clone 联网，commit/add/reset 写入；</li>
 *   <li>构建工具（mvn/gradle）→ 写入 + 联网（需拉依赖）；</li>
 *   <li>网络工具（curl/wget/nc/ssh）→ 联网；</li>
 *   <li>无法识别的命令 → 一律按写入处理（保守，宁可多问一次）。</li>
 * </ul>
 * 由于命令不经 shell 解释器执行，管道/重定向/glob 不生效，因此无需担心 {@code curl ... | sh} 这类注入。
 * @date 2026/9/21
 */
public class CommandClassifier {

    /**
     * 只读可执行文件：读取信息、不产生副作用
     */
    private static final Set<String> READ_ONLY_EXE = Set.of(
            "pwd", "ls", "cat", "head", "tail", "grep", "egrep", "fgrep", "rg", "find",
            "wc", "echo", "which", "whereis", "file", "stat", "du", "df", "tree", "basename", "dirname");

    /**
     * 破坏性可执行文件：一旦执行难以回滚，默认按危险处理
     */
    private static final Set<String> DANGEROUS_EXE = Set.of(
            "rm", "mkfs", "dd", "shutdown", "reboot", "halt", "poweroff", "mkswap",
            "fdisk", "parted", "chmod", "chown", "chgrp", "kill", "killall", "pkill");

    /**
     * 联网可执行文件：会产生网络请求
     */
    private static final Set<String> NETWORK_EXE = Set.of(
            "curl", "wget", "nc", "ncat", "ssh", "scp", "sftp", "ftp", "telnet", "ping");

    /**
     * 构建工具：既写文件又联网
     */
    private static final Set<String> BUILD_EXE = Set.of("mvn", "gradle", "gradlew", "npm", "yarn", "pnpm", "pip", "cargo");

    /**
     * git 只读子命令
     */
    private static final Set<String> GIT_READ_SUB = Set.of(
            "status", "log", "diff", "show", "blame", "ls-files", "ls-tree", "describe",
            "rev-parse", "rev-list", "shortlog", "whatchanged", "reflog", "cat-file", "name-rev");

    /**
     * git 联网子命令
     */
    private static final Set<String> GIT_NETWORK_SUB = Set.of("push", "pull", "fetch", "clone", "ls-remote");

    private CommandClassifier() {
    }

    /**
     * 分类结果：一组布尔标志 + 命中原因，便于决策与日志
     */
    public static class Risk {
        private final boolean readOnly;
        private final boolean writes;
        private final boolean network;
        private final boolean dangerous;
        private final String reason;

        Risk(boolean readOnly, boolean writes, boolean network, boolean dangerous, String reason) {
            this.readOnly = readOnly;
            this.writes = writes;
            this.network = network;
            this.dangerous = dangerous;
            this.reason = reason;
        }

        public boolean isReadOnly() {
            return readOnly;
        }

        public boolean isWrites() {
            return writes;
        }

        public boolean isNetwork() {
            return network;
        }

        public boolean isDangerous() {
            return dangerous;
        }

        public String getReason() {
            return reason;
        }
    }

    /**
     * 对命令数组分类。空命令视为只读（无从判断，交给上层做空校验）
     */
    public static Risk classify(List<String> command) {
        if (command == null || command.isEmpty()) {
            return new Risk(true, false, false, false, "empty command");
        }
        String exe = SandboxPolicy.basename(command.get(0)).toLowerCase(Locale.ROOT);

        if ("git".equals(exe)) {
            return classifyGit(command);
        }
        if (DANGEROUS_EXE.contains(exe)) {
            // rm 等即便无危险参数也按危险处理，宁可要求确认
            return new Risk(false, true, false, true, "destructive executable: " + exe);
        }
        if (BUILD_EXE.contains(exe)) {
            return new Risk(false, true, true, false, "build tool (write+network): " + exe);
        }
        if (NETWORK_EXE.contains(exe)) {
            return new Risk(false, false, true, false, "network tool: " + exe);
        }
        if (READ_ONLY_EXE.contains(exe)) {
            return new Risk(true, false, false, false, "read-only executable: " + exe);
        }
        // 未知命令保守按写入处理，触发工作区路径校验
        return new Risk(false, true, false, false, "unknown executable, treat as write: " + exe);
    }

    /**
     * git 按子命令细分：跳过全局选项（以 - 开头）找到第一个子命令
     */
    private static Risk classifyGit(List<String> command) {
        String sub = null;
        for (int i = 1; i < command.size(); i++) {
            String arg = command.get(i);
            if (!arg.startsWith("-")) {
                sub = arg.toLowerCase(Locale.ROOT);
                break;
            }
        }
        if (sub == null) {
            return new Risk(true, false, false, false, "git without subcommand");
        }
        if (GIT_READ_SUB.contains(sub)) {
            return new Risk(true, false, false, false, "git read subcommand: " + sub);
        }
        if (GIT_NETWORK_SUB.contains(sub)) {
            return new Risk(false, true, true, false, "git network subcommand: " + sub);
        }
        // reset --hard / clean -fd 等破坏性操作单独标记为危险
        if (("reset".equals(sub) || "clean".equals(sub) || "checkout".equals(sub)) && hasForceFlag(command)) {
            return new Risk(false, true, false, true, "git destructive subcommand: " + sub);
        }
        return new Risk(false, true, false, false, "git write subcommand: " + sub);
    }

    private static boolean hasForceFlag(List<String> command) {
        for (String arg : command) {
            if (arg.equals("--hard") || arg.equals("-f") || arg.equals("--force")
                    || arg.equals("-fd") || arg.equals("-df") || arg.startsWith("-f")) {
                return true;
            }
        }
        return false;
    }
}
