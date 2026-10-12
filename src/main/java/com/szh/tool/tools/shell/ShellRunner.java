package com.szh.tool.tools.shell;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 子进程执行器：把「ProcessBuilder + redirectErrorStream + 独立读线程 + waitFor 超时 + destroyForcibly」
 * 从 {@link ShellCommandTool} 抽出，供 shell 工具与验证闭环（{@code com.szh.agent.verify.Verifier}）复用，
 * 避免两套子进程执行逻辑各自演化（对齐项目「复用而非各写一套」约定）。
 * <p>
 * 只负责「起进程、收输出、管超时」，不含权限决策与沙箱包装——调用方必须先自行过
 * {@code ShellSecurity.authorize/wrap}，把安全闸门保持在唯一入口处。
 *
 * @author demussong
 * @date 2026/10/9
 */
@Slf4j
public final class ShellRunner {

    /**
     * 进程退出后等待输出读完的时间（秒）
     */
    private static final long DRAIN_SECONDS = 5;

    private ShellRunner() {
    }

    /**
     * 执行结果：
     * <ul>
     *   <li>{@code exitCode}：进程退出码；超时或异常时为 -1；</li>
     *   <li>{@code output}：合并后的 stdout+stderr；</li>
     *   <li>{@code timedOut}：是否因超时被强制中断；</li>
     *   <li>{@code error}：启动/执行异常信息，无异常为 null。</li>
     * </ul>
     */
    public record ShellResult(int exitCode, String output, boolean timedOut, String error) {
    }

    /**
     * 在指定工作目录起子进程执行命令，独立线程读输出，超时即销毁进程。
     *
     * @param command        已包装（沙箱）的命令 argv，第一个元素为可执行文件
     * @param workdir        工作目录，null 表示沿用 JVM 当前目录
     * @param env            额外环境变量，null/空表示不注入
     * @param timeoutSeconds 最长执行时间（秒）
     */
    public static ShellResult run(List<String> command, File workdir, Map<String, String> env, long timeoutSeconds) {
        // 读输出放到独立线程：主线程 waitFor 超时后可直接销毁进程，不会卡在 readLine 上
        ExecutorService reader = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "shell-output-runner");
            thread.setDaemon(true);
            return thread;
        });
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
            if (workdir != null) {
                builder.directory(workdir);
            }
            if (env != null && !env.isEmpty()) {
                builder.environment().putAll(env);
            }

            Process started = builder.start();
            process = started;
            Future<String> output = reader.submit(() -> readOutput(started));

            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new ShellResult(-1, "", true, null);
            }

            String raw = output.get(DRAIN_SECONDS, TimeUnit.SECONDS);
            int exitCode = process.exitValue();
            return new ShellResult(exitCode, raw, false, null);
        } catch (Exception e) {
            log.error("execute command failed: {}", String.join(" ", command), e);
            return new ShellResult(-1, "", false, e.getMessage());
        } finally {
            reader.shutdownNow();
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static String readOutput(Process process) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }
}
