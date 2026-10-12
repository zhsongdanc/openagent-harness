package com.szh.agent.verify;

import com.szh.tool.security.PermissionDecision;
import com.szh.tool.security.ShellSecurity;
import com.szh.tool.tools.shell.ShellRunner;
import com.szh.utils.ConfigUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 验证执行器：按 {@link VerifyConfig#getCommands()} 顺序跑验证命令，fail-fast——首个失败即停并回传结果。
 * <p>
 * 执行链路完全复用既有安全设施：命令先过 {@code ShellSecurity.authorize}（权限闸门），再 {@code ShellSecurity.wrap}
 * （OS 级沙箱），最后交 {@link ShellRunner} 起子进程收输出。命令串按空白拆成 argv，不经 shell 解释器，无注入风险。
 * <p>
 * 失败输出按 {@code tool.result.max.chars} 预算做<b>尾部</b>截断（编译/测试报错通常在末尾），避免撑爆回灌消息。
 *
 * @author demussong
 * @date 2026/10/9
 */
@Slf4j
public final class Verifier {

    private Verifier() {
    }

    /**
     * 执行全部验证命令。
     *
     * @param cfg       验证配置；命令为空时直接判为通过（无验证可做）
     * @param workspace 工作区目录，命令在此目录下执行
     * @return 首个失败的结果，或全部通过
     */
    public static VerifyResult verify(VerifyConfig cfg, String workspace) {
        if (cfg == null || cfg.getCommands() == null || cfg.getCommands().isEmpty()) {
            return VerifyResult.pass();
        }
        int maxChars = ConfigUtil.getInt("tool.result.max.chars", 4000);
        File workdir = resolveWorkspace(workspace);

        for (String cmdStr : cfg.getCommands()) {
            List<String> command = splitCommand(cmdStr);
            if (command.isEmpty()) {
                continue;
            }
            // 安全闸门：验证命令同样先过权限决策，被拒即视为验证失败（原因回灌给模型/用户）
            PermissionDecision decision = ShellSecurity.authorize(command, workspace);
            if (!decision.isAllowed()) {
                log.warn("verify command blocked by security policy: {} ({})", cmdStr, decision.getReason());
                return VerifyResult.blocked(cmdStr, "被安全策略拦截：" + decision.getReason());
            }
            List<String> exec = ShellSecurity.wrap(command, workspace);
            log.info("verify exec: {}", cmdStr);

            ShellRunner.ShellResult r = ShellRunner.run(exec, workdir, null, cfg.getTimeoutSeconds());
            if (r.error() != null) {
                return VerifyResult.blocked(cmdStr, "执行异常：" + r.error());
            }
            if (r.timedOut()) {
                return VerifyResult.blocked(cmdStr, "验证命令超时（" + cfg.getTimeoutSeconds() + "s）已中断");
            }
            if (r.exitCode() != 0) {
                log.info("verify failed: cmd={}, exitCode={}", cmdStr, r.exitCode());
                return VerifyResult.fail(cmdStr, r.exitCode(), tailTruncate(r.output(), maxChars));
            }
            log.info("verify passed: {}", cmdStr);
        }
        return VerifyResult.pass();
    }

    /**
     * 命令串按空白拆成 argv（与 {@code ShellCommandTool.appendArgs} 一致，不经 shell 解释器）
     */
    static List<String> splitCommand(String cmdStr) {
        List<String> argv = new ArrayList<>();
        if (cmdStr == null || cmdStr.isBlank()) {
            return argv;
        }
        for (String part : cmdStr.trim().split("\\s+")) {
            argv.add(part);
        }
        return argv;
    }

    /**
     * 尾部保留式截断：只留最后 maxChars 个字符，前面用省略标记，确保报错末尾不被裁掉
     */
    static String tailTruncate(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        if (maxChars <= 0 || text.length() <= maxChars) {
            return text;
        }
        int omitted = text.length() - maxChars;
        return "...[省略前 " + omitted + " 字符]...\n" + text.substring(omitted);
    }

    /**
     * 工作目录取工作区根目录，未配置或目录不存在时返回 null，表示沿用 JVM 当前目录
     */
    private static File resolveWorkspace(String workspace) {
        if (workspace == null || workspace.isBlank()) {
            return null;
        }
        File dir = new File(workspace);
        return dir.isDirectory() ? dir : null;
    }
}
