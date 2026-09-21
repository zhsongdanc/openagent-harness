package com.szh.tool.security;

import com.szh.utils.ConfigUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author demussong
 * @describe shell 安全门面：把「权限决策 + 沙箱包装」收敛成两个静态入口，供 {@code ShellCommandTool} 调用。
 * <p>
 * {@link PermissionController} 按工作区缓存（同一工作区只装配一次）；{@link SandboxExecutor} 进程内单例。
 * 总开关 {@code shell.security.enabled} 关闭后，授权一律放行、命令不再包装，退回改造前的直接执行行为。
 * @date 2026/9/21
 */
@Slf4j
public class ShellSecurity {

    private static final Map<String, PermissionController> CONTROLLERS = new ConcurrentHashMap<>();

    private static final SandboxExecutor SANDBOX = SandboxFactory.get();

    private ShellSecurity() {
    }

    /**
     * 总开关：默认开启
     */
    public static boolean isEnabled() {
        return ConfigUtil.getBoolean("shell.security.enabled", true);
    }

    private static PermissionController controller(String workspace) {
        String key = workspace == null ? "__default__" : workspace;
        return CONTROLLERS.computeIfAbsent(key, k ->
                PermissionController.fromConfig("__default__".equals(k) ? null : k));
    }

    /**
     * 命令授权：返回三态决策（含人工确认后的最终 ALLOW/DENY）
     */
    public static PermissionDecision authorize(List<String> command, String workspace) {
        if (!isEnabled()) {
            return PermissionDecision.allow("security disabled");
        }
        return controller(workspace).authorize(command);
    }

    /**
     * 沙箱包装：按当前档位把命令包进 OS 级隔离；未启用沙箱或 FULL_ACCESS 时原样返回
     */
    public static List<String> wrap(List<String> command, String workspace) {
        if (!isEnabled()) {
            return command;
        }
        PermissionController c = controller(workspace);
        if (!c.getPolicy().isSandboxEnabled()) {
            return command;
        }
        return SANDBOX.wrap(command, c.getPolicy());
    }
}
