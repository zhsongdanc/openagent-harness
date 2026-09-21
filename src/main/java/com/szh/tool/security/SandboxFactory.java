package com.szh.tool.security;

import lombok.extern.slf4j.Slf4j;

/**
 * @author demussong
 * @describe 沙箱执行器工厂：按当前平台选择合适的隔离实现，进程内单例复用。
 * <p>
 * macOS 用 {@link SeatbeltSandbox}（sandbox-exec）；其它平台暂降级为 {@link DirectSandbox}，
 * 安全性由 {@link PermissionController} 策略层兜底。Linux 的 Landlock/seccomp 实现预留在此扩展。
 * @date 2026/9/21
 */
@Slf4j
public class SandboxFactory {

    private static final SandboxExecutor INSTANCE = create();

    private SandboxFactory() {
    }

    public static SandboxExecutor get() {
        return INSTANCE;
    }

    private static SandboxExecutor create() {
        SeatbeltSandbox seatbelt = new SeatbeltSandbox();
        if (seatbelt.isAvailable()) {
            log.info("sandbox executor: seatbelt (macOS sandbox-exec)");
            return seatbelt;
        }
        log.info("sandbox executor: direct (no OS-level isolation on this platform, rely on permission layer)");
        return new DirectSandbox();
    }
}
