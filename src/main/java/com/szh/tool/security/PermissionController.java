package com.szh.tool.security;

import com.szh.utils.ConfigUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * @author demussong
 * @describe 权限控制器：命令执行前的策略闸门，对标 Codex 的档位模型 + Claude Code 的 allow/ask/deny 规则。
 * <p>
 * 决策综合三方信息：
 * <ol>
 *   <li>{@link CommandClassifier} 的风险分类（只读/写入/联网/危险）；</li>
 *   <li>{@link PathGuard} 的路径越界校验；</li>
 *   <li>{@link SandboxPolicy} 的当前档位与联网豁免名单。</li>
 * </ol>
 * 决策三态：ALLOW 直接执行、DENY 拒绝并回传原因、NEED_CONFIRM 交 {@link PermissionPrompter} 人工确认。
 * <p>
 * 设计原则：FULL_ACCESS 档位完全放行；危险命令默认「确认」（可配 deny/confirm/allow）；
 * 只读档位拒绝一切写入与联网；工作区可写档位下，联网仅豁免名单内工具可用，写操作严格限制在允许根内。
 * @date 2026/9/21
 */
@Slf4j
public class PermissionController {

    private final SandboxPolicy policy;
    private final PermissionPrompter prompter;

    /**
     * 危险命令处理策略：deny / confirm / allow
     */
    private final String dangerousAction;

    /**
     * 越界读取处理策略：deny / confirm / allow
     */
    private final String outsideReadAction;

    public PermissionController(SandboxPolicy policy, PermissionPrompter prompter,
                                String dangerousAction, String outsideReadAction) {
        this.policy = policy;
        this.prompter = prompter;
        this.dangerousAction = dangerousAction;
        this.outsideReadAction = outsideReadAction;
    }

    /**
     * 从配置装配控制器（策略 + 确认器 + 各类动作阈值）
     */
    public static PermissionController fromConfig(String workspace) {
        SandboxPolicy policy = SandboxPolicy.fromConfig(workspace);
        boolean interactive = ConfigUtil.getBoolean("shell.permission.interactive", true);
        boolean autoApprove = ConfigUtil.getBoolean("shell.permission.autoApprove", false);
        PermissionPrompter prompter = interactive
                ? new ConsolePermissionPrompter(autoApprove)
                : new AutoPermissionPrompter(autoApprove);
        String dangerous = ConfigUtil.get("shell.permission.dangerous", "confirm");
        String outsideRead = ConfigUtil.get("shell.permission.outsideRead", "confirm");
        return new PermissionController(policy, prompter, dangerous, outsideRead);
    }

    public SandboxPolicy getPolicy() {
        return policy;
    }

    /**
     * 纯决策，不触发人工确认。返回三态之一
     */
    public PermissionDecision evaluate(List<String> command) {
        if (command == null || command.isEmpty()) {
            return PermissionDecision.deny("empty command");
        }
        // FULL_ACCESS：完全放开，等价宿主机直接执行
        if (policy.getMode() == SandboxMode.FULL_ACCESS) {
            return PermissionDecision.allow("full-access mode");
        }

        String exe = SandboxPolicy.basename(command.get(0));
        CommandClassifier.Risk risk = CommandClassifier.classify(command);
        PathGuard.PathCheck pathCheck = PathGuard.check(command, policy);

        // 1. 危险命令：按配置 deny/confirm/allow
        if (risk.isDangerous()) {
            PermissionDecision d = applyAction(dangerousAction,
                    "危险命令 [" + String.join(" ", command) + "]：" + risk.getReason());
            if (!d.isAllowed()) {
                return d;
            }
            // allow 时继续走后续路径/网络校验
        }

        // 2. 写入类命令
        if (risk.isWrites()) {
            if (policy.getMode() == SandboxMode.READ_ONLY) {
                return PermissionDecision.deny("只读档位禁止写操作：" + risk.getReason());
            }
            if (pathCheck.isOutside()) {
                return PermissionDecision.deny("写操作路径越界（超出工作区/允许根）：" + pathCheck.getOutsidePaths());
            }
        }

        // 3. 只读命令但访问越界路径：按配置 deny/confirm/allow
        if (risk.isReadOnly() && (pathCheck.isOutside() || pathCheck.hasTraversal())) {
            return applyAction(outsideReadAction,
                    "读取越界路径 " + pathCheck.getOutsidePaths() + "（traversal=" + pathCheck.hasTraversal() + "）");
        }

        // 4. 联网命令
        if (risk.isNetwork()) {
            if (policy.getMode() == SandboxMode.READ_ONLY) {
                return PermissionDecision.deny("只读档位禁止联网：" + risk.getReason());
            }
            if (!policy.isNetworkAllowed(exe)) {
                return PermissionDecision.deny("工作区可写档位默认禁网，" + exe
                        + " 不在联网豁免名单（shell.sandbox.network.tools）内");
            }
        }

        return PermissionDecision.allow(risk.getReason());
    }

    /**
     * 决策 + 处理人工确认：NEED_CONFIRM 会调用确认器，转成最终 ALLOW/DENY。
     * 这是 ShellCommandTool 应调用的入口。
     */
    public PermissionDecision authorize(List<String> command) {
        PermissionDecision decision = evaluate(command);
        if (!decision.needsConfirm()) {
            return decision;
        }
        String cmdline = String.join(" ", command);
        log.warn("command needs confirmation: {}", cmdline);
        boolean approved = prompter.confirm("命令: " + cmdline + "\n原因: " + decision.getReason());
        return approved
                ? PermissionDecision.allow("user approved: " + decision.getReason())
                : PermissionDecision.deny("user denied: " + decision.getReason());
    }

    /**
     * 把动作字符串（deny/confirm/allow）落成决策，未知值按 confirm 保守处理
     */
    private PermissionDecision applyAction(String action, String reason) {
        String a = action == null ? "confirm" : action.trim().toLowerCase();
        return switch (a) {
            case "deny" -> PermissionDecision.deny(reason);
            case "allow" -> PermissionDecision.allow(reason);
            default -> PermissionDecision.confirm(reason);
        };
    }
}
