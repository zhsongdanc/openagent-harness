package com.szh.tool.security;

import java.util.List;

/**
 * @author demussong
 * @describe 直连沙箱（降级实现）：不做任何 OS 级隔离，命令原样交给 ProcessBuilder 在宿主机执行。
 * <p>
 * 用于两种场景：FULL_ACCESS 档位；或当前平台不支持 Seatbelt/Landlock（如 Windows、未装 bwrap 的 Linux）。
 * 此时安全性完全依赖 {@link PermissionController} 的策略层兜底。
 * @date 2026/9/21
 */
public class DirectSandbox implements SandboxExecutor {

    @Override
    public List<String> wrap(List<String> command, SandboxPolicy policy) {
        return command;
    }

    @Override
    public String name() {
        return "direct";
    }

    @Override
    public boolean isAvailable() {
        return true;
    }
}
