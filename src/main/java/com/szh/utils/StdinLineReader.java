package com.szh.utils;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * @author demussong
 * @describe 全局唯一的标准输入行读取器。
 * <p>
 * 为什么需要它：{@code BufferedReader} 一次会从底层 fd 吞最多 8192 字符进自己的内部缓冲，
 * 若 REPL 主循环与权限确认（{@code ConsolePermissionPrompter}）各自 new 一个 reader 读同一个
 * {@code System.in}，用户预输入的行会被其中一个 reader 吞进内部缓冲，另一个永远看不见——
 * 表现为"敲了没反应"或"你说的话被当成 y/N 吃了"的串台。一个 fd 只能有一个缓冲，
 * 所以所有 stdin 读取统一走这里，readLine 加锁保证多线程（并行工具确认）下按行不交错。
 * @date 2026-10-15
 */
@Slf4j
public final class StdinLineReader {

    private static final StdinLineReader INSTANCE = new StdinLineReader();

    /**
     * 进程生命周期内唯一持有 System.in 的缓冲 reader，杜绝多缓冲丢行
     */
    private final BufferedReader reader =
            new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));

    private StdinLineReader() {
    }

    public static StdinLineReader shared() {
        return INSTANCE;
    }

    /**
     * 读一行。加锁：并行工具执行时多个确认提示可能并发到达，无锁会出现一行被错处消费。
     *
     * @return 读到的一行；null 表示 EOF（非交互环境或输入已结束）
     */
    public synchronized String readLine() {
        try {
            return reader.readLine();
        } catch (Exception e) {
            // stdin 读失败按 EOF 语义处理，由调用方决定兜底（如权限确认默认拒绝）
            log.warn("read stdin line failed, treat as EOF", e);
            return null;
        }
    }
}
