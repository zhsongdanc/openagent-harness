package com.szh.tool.security;

import java.util.List;

/**
 * @author demussong
 * @describe 沙箱执行器：把原始命令包装成「在 OS 级隔离下执行」的等价命令。
 * <p>
 * 对标 Codex CLI 的做法——不用 Docker，而是用操作系统内核自带的沙箱原语（macOS Seatbelt / Linux Landlock），
 * 进程级隔离、零启动开销。不同平台一个实现，无法隔离时降级为 {@link DirectSandbox}（直接执行，仅靠权限层兜底）。
 * <p>
 * 实现只需关心：给定命令与策略，返回可直接交给 ProcessBuilder 的命令数组（可能是加了前缀的新数组）。
 * @date 2026/9/21
 */
public interface SandboxExecutor {

    /**
     * 包装命令。返回的数组第一个元素是可执行文件，可能是 sandbox-exec 之类的包装器
     *
     * @param command 原始命令数组
     * @param policy  当前沙箱策略（档位、可写根、联网豁免）
     */
    List<String> wrap(List<String> command, SandboxPolicy policy);

    /**
     * 执行器名称，用于日志
     */
    String name();

    /**
     * 当前平台是否真正可用（不可用时工厂会降级为 DirectSandbox）
     */
    boolean isAvailable();
}
