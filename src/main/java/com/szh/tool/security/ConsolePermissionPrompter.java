package com.szh.tool.security;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * @author demussong
 * @describe 控制台确认器：把待确认命令打印到 stderr，从标准输入读取 y/n。
 * <p>
 * 交互不可用（无控制台、输入流 EOF，如批处理或单测）时，退回构造时给定的默认决策，
 * 默认拒绝以保证「无人值守时不做危险操作」的安全底线。
 * @date 2026/9/21
 */
@Slf4j
public class ConsolePermissionPrompter implements PermissionPrompter {

    /**
     * 无法交互时的兜底决策
     */
    private final boolean defaultWhenNonInteractive;

    public ConsolePermissionPrompter(boolean defaultWhenNonInteractive) {
        this.defaultWhenNonInteractive = defaultWhenNonInteractive;
    }

    /**
     * 确认入口加锁：并行工具执行时多个 worker 可能同时触发确认，
     * 不加锁会出现提示交错、stdin 应答串台（A 命令的 y 被 B 命令消费）
     */
    @Override
    public synchronized boolean confirm(String prompt) {
        // 提示写到 stderr，避免污染程序正常 stdout 输出
        System.err.println();
        System.err.println("========== 需要确认 ==========");
        System.err.println(prompt);
        System.err.print("允许执行吗？(y/N): ");
        System.err.flush();

        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            String line = reader.readLine();
            if (line == null) {
                // EOF：非交互环境
                log.warn("permission prompt got EOF (non-interactive), fallback to {}", defaultWhenNonInteractive);
                return defaultWhenNonInteractive;
            }
            String answer = line.trim().toLowerCase();
            return answer.equals("y") || answer.equals("yes");
        } catch (Exception e) {
            log.warn("read confirmation failed, fallback to {}", defaultWhenNonInteractive, e);
            return defaultWhenNonInteractive;
        }
    }
}
