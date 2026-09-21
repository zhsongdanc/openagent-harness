package com.szh.agent;

import com.szh.utils.ConfigUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * @author demussong
 * @describe 循环熔断器：在 run 循环中检测「死循环 / 持续失败」，及时刹车，避免无谓烧 token 和卡死。
 * <p>
 * 对标主流 harness 的可靠性护栏，检测两类异常模式：
 * <ol>
 *   <li><b>连续失败</b>：工具连续抛异常或返回错误结果达到阈值——模型可能陷入无法自愈的错误；</li>
 *   <li><b>重复调用</b>：同一工具 + 同一参数被连续重复调用达到阈值——模型在原地打转。</li>
 * </ol>
 * 一旦触发即置为 sticky 的 tripped 状态，运行时据此结束本次 run 并回传原因。
 * 每个 run 应新建一个实例（状态是 run 级的）。
 * @date 2026/9/21
 */
@Slf4j
public class LoopGuard {

    /**
     * 熔断事件：类型 + 可读原因
     */
    public static class Violation {
        private final String type;
        private final String reason;

        Violation(String type, String reason) {
            this.type = type;
            this.reason = reason;
        }

        public String getType() {
            return type;
        }

        public String getReason() {
            return reason;
        }
    }

    private final boolean enabled;
    private final int maxConsecutiveFailures;
    private final int maxRepeatCalls;

    private int consecutiveFailures = 0;
    private String lastCallKey = null;
    private int repeatCount = 0;

    /**
     * sticky 熔断状态，一旦触发不再清除
     */
    private Violation tripped;

    public LoopGuard() {
        this.enabled = ConfigUtil.getBoolean("agent.guard.enabled", true);
        this.maxConsecutiveFailures = ConfigUtil.getInt("agent.guard.maxConsecutiveFailures", 5);
        this.maxRepeatCalls = ConfigUtil.getInt("agent.guard.maxRepeatCalls", 4);
    }

    /**
     * 记录一次工具调用结果，必要时触发熔断。
     * 内部计数器非原子，并行工具执行时由 ParallelToolExecutor 在主线程串行回放调用，
     * 这里再加 synchronized 双保险，避免未来其它并发路径踩坑。
     *
     * @param toolCode  工具名
     * @param args      调用参数（用于重复检测）
     * @param result    工具返回文本
     * @param threw     工具执行是否抛异常
     */
    public synchronized void record(String toolCode, String args, String result, boolean threw) {
        if (!enabled || tripped != null) {
            return;
        }

        // 1. 连续失败检测
        boolean failed = threw || isErrorResult(result);
        if (failed) {
            consecutiveFailures++;
            if (consecutiveFailures >= maxConsecutiveFailures) {
                trip("consecutive-failures",
                        "工具连续失败 " + consecutiveFailures + " 次（阈值 " + maxConsecutiveFailures + "），已熔断");
                return;
            }
        } else {
            consecutiveFailures = 0;
        }

        // 2. 重复调用检测（同一工具 + 同一参数连续出现）
        String key = toolCode + "|" + (args == null ? "" : args.trim());
        if (key.equals(lastCallKey)) {
            repeatCount++;
        } else {
            lastCallKey = key;
            repeatCount = 1;
        }
        if (repeatCount >= maxRepeatCalls) {
            trip("repeat-calls",
                    "工具 [" + toolCode + "] 以相同参数连续调用 " + repeatCount + " 次（阈值 "
                            + maxRepeatCalls + "），疑似死循环，已熔断");
        }
    }

    private void trip(String type, String reason) {
        this.tripped = new Violation(type, reason);
        log.warn("[LoopGuard] {}: {}", type, reason);
    }

    /**
     * 是否已熔断
     */
    public boolean isTripped() {
        return tripped != null;
    }

    public Violation getViolation() {
        return tripped;
    }

    /**
     * 启发式判断工具返回是否为错误结果。覆盖运行时/工具层已知的错误前缀与非零退出码标记
     */
    private boolean isErrorResult(String result) {
        if (result == null || result.isBlank()) {
            return false;
        }
        String r = result;
        return r.contains("被安全策略拦截")
                || r.contains("参数错误")
                || r.contains("参数解析失败")
                || r.contains("执行超时")
                || r.contains("execute failed")
                || r.contains("tool not found")
                || r.contains("未能拼出可执行命令")
                || r.matches("(?s).*exitCode=[1-9]\\d*.*");
    }
}
