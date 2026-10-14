package com.szh.agent;

import com.szh.context.dto.MessageItem;
import com.szh.context.dto.SystemMessageItem;
import com.szh.utils.ConfigUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @author demussong
 * @describe 循环护栏：在 run 循环中检测「死循环 / 工具持续故障」，分级刹车，避免无谓烧 token 和卡死。
 * <p>
 * 对标主流 harness（Claude Code / Codex）的可靠性护栏，遵循三条工程原则：
 * <ol>
 *   <li><b>先分类再计数</b>：把工具返回分成「工具故障」（异常/超时/参数错误/工具不存在，重试无意义或需换方法）
 *       与「命令业务失败」（{@code exitCode!=0}，如编译/测试没过）。后者是 coding agent「测试-修复」循环里
 *       最正常不过的反馈信号，<b>不计入</b>连续故障，否则正常工作流会被误杀；它交给重复检测兜底。</li>
 *   <li><b>分级升级，逐级透明</b>：连续故障达软阈值先注入一条纠偏提示（{@link #decorateContext}）给模型
 *       改策略的机会，仍无效才硬熔断（TERMINATE）结束 run；而不是从「正常」直接跳到「终止」。</li>
 *   <li><b>每条恢复路径独立上限</b>：除全局连续故障计数外，按工具维度独立计数，某个工具反复故障单独点名提示、
 *       独立熔断，不被其它工具的成功稀释。</li>
 * </ol>
 * 另设「重复调用」检测（同一工具+同一参数连续出现）识别原地打转，并用<b>进展信用</b>豁免 fix-then-retry
 * （改完代码重跑相同命令不算死循环）。熔断为 sticky，触发即结束本次 run 并回传原因（附已尝试的恢复动作）。
 * <p>
 * LoopGuard 是 run 级实例（每次 {@code run()} 新建），跨轮/跨会话天然重置，故不设时间冷却的 half-open 三态；
 * 「给模型改策略机会」由软提示档承担。每个 run 应新建一个实例。
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

    /**
     * 工具调用结果的故障分类：决定是否计入熔断、以及给模型怎样的纠偏提示。
     */
    private enum FaultKind {
        /** 成功，无故障 */
        NONE,
        /** 命令执行了但业务失败（exitCode!=0）——正常反馈，不计入连续故障，交给重复检测兜底 */
        LOGICAL,
        /** 工具故障-偏瞬时（超时/抛异常/连接失败），可能重试有效 */
        TOOL_TRANSIENT,
        /** 工具故障-不可重试（参数错误/权限拦截/工具不存在），原样重试无意义，须改输入或换方法 */
        TOOL_FATAL
    }

    /**
     * 授予「进展信用」的写入类工具：它们成功执行意味着仓库状态被推进，
     * 紧接着重跑相同命令（如改完代码再跑同一条测试）属 fix-then-retry，不应判为死循环。
     */
    private static final Set<String> PROGRESS_CODES = Set.of(
            "write_file", "edit_file", "delete_file", "git_add", "git_commit", "git_push");

    private final boolean enabled;
    private final int maxConsecutiveFailures;
    private final int maxRepeatCalls;
    private final int adviseAfterFailures;
    private final int maxFailuresPerTool;
    private final int advisePerToolAfter;

    /** 全局连续故障计数（仅统计 TOOL_TRANSIENT / TOOL_FATAL） */
    private int consecutiveFailures = 0;
    /** 按工具维度的连续故障计数：key=toolCode，工具成功执行即清除 */
    private final Map<String, Integer> perToolFaults = new HashMap<>();

    private String lastCallKey = null;
    private int repeatCount = 0;
    /** 自上次重复判定以来是否有成功的写入类工具（fix-then-retry 信用） */
    private boolean progressCredit = false;
    /** 最近一次故障分类：供全局纠偏提示定制文案 */
    private FaultKind lastFaultKind = FaultKind.NONE;

    /** 本轮累积、待下一轮注入的纠偏软提示；decorateContext 取走后清空 */
    private final StringBuilder pendingAdvice = new StringBuilder();
    /**
     * 已送达模型的提示次数：decorateContext 每带走一批提示 +1。硬熔断前要求 ≥1——
     * 保证模型真正看过至少一轮纠偏提示后仍继续故障才允许 trip，修复「同轮并行失败
     * 瞬间打满阈值时模型至死没见过提示」的误杀缺口；被缓下的计数不过期（sticky 累加），
     * 下一轮提示送达后再故障一次即正常熔断。
     */
    private int adviceDelivered = 0;
    /** 全局软提示只发一次，避免每轮刷屏 */
    private boolean issuedGlobalAdvice = false;
    /** 已点名提示过的工具，每个只发一次 */
    private final Set<String> advisedTools = new HashSet<>();

    /**
     * sticky 熔断状态，一旦触发不再清除
     */
    private Violation tripped;

    public LoopGuard() {
        this.enabled = ConfigUtil.getBoolean("agent.guard.enabled", true);
        this.maxConsecutiveFailures = ConfigUtil.getInt("agent.guard.maxConsecutiveFailures", 5);
        this.maxRepeatCalls = ConfigUtil.getInt("agent.guard.maxRepeatCalls", 4);
        this.adviseAfterFailures = ConfigUtil.getInt("agent.guard.adviseAfterFailures", 3);
        this.maxFailuresPerTool = ConfigUtil.getInt("agent.guard.maxFailuresPerTool", 4);
        this.advisePerToolAfter = ConfigUtil.getInt("agent.guard.advisePerToolAfter", 2);
    }

    /**
     * 记录一次工具调用结果，必要时升级（软提示）或触发熔断。
     * 内部计数器非原子，并行工具执行时由 ParallelToolExecutor 在主线程串行回放调用，
     * 这里再加 synchronized 双保险，避免未来其它并发路径踩坑。
     *
     * @param toolCode 工具名
     * @param args     调用参数（用于重复检测）
     * @param result   工具返回文本
     * @param threw    工具执行是否抛异常
     */
    public synchronized void record(String toolCode, String args, String result, boolean threw) {
        if (!enabled || tripped != null) {
            return;
        }

        FaultKind kind = classify(result, threw);
        lastFaultKind = kind;
        boolean toolFault = kind == FaultKind.TOOL_TRANSIENT || kind == FaultKind.TOOL_FATAL;

        // 1. 连续故障检测（只统计工具自身故障；命令业务失败 exitCode!=0 是正常反馈，不计入，
        //    否则 coding agent 的「测试-修复」循环会被误杀——那由 VerifyGate 与重复检测负责）
        if (toolFault) {
            consecutiveFailures++;
            int toolFaults = perToolFaults.merge(toolCode, 1, Integer::sum);

            // 1a. 单工具路径：达硬阈值先熔断，否则达软阈值点名提示（每工具一次）
            if (toolFaults >= maxFailuresPerTool && canTrip()) {
                trip("tool-failures",
                        "工具 [" + toolCode + "] 连续故障 " + toolFaults + " 次（阈值 " + maxFailuresPerTool
                                + "），已熔断" + recoverySuffix());
                return;
            }
            if (toolFaults >= advisePerToolAfter && advisedTools.add(toolCode)) {
                appendAdvice("工具 [" + toolCode + "] 已连续故障 " + toolFaults
                        + " 次，请停止重复调用它，改用其他工具或方法达成目标。");
            }

            // 1b. 全局路径：达硬阈值熔断，否则达软阈值注入纠偏提示（一次）
            if (consecutiveFailures >= maxConsecutiveFailures && canTrip()) {
                trip("consecutive-failures",
                        "工具连续故障 " + consecutiveFailures + " 次（阈值 " + maxConsecutiveFailures
                                + "），已熔断" + recoverySuffix());
                return;
            }
            if (consecutiveFailures >= adviseAfterFailures && !issuedGlobalAdvice) {
                issuedGlobalAdvice = true;
                appendAdvice(globalAdviceText());
            }
        } else {
            // 成功或仅命令业务失败：工具本身可正常执行，清零全局与该工具的故障计数
            consecutiveFailures = 0;
            perToolFaults.remove(toolCode);
            // 成功的写入类工具授予进展信用：fix-then-retry（改完代码重跑相同命令）不算死循环
            if (kind == FaultKind.NONE && PROGRESS_CODES.contains(toolCode)) {
                progressCredit = true;
            }
        }

        // 2. 重复调用检测（同一工具 + 同一参数连续出现）
        String key = toolCode + "|" + (args == null ? "" : args.trim());
        if (key.equals(lastCallKey)) {
            if (progressCredit) {
                // 期间改过代码/提交过（有实质进展），相同命令的重跑属 fix-then-retry，消耗信用并重置而非累加
                progressCredit = false;
                repeatCount = 1;
            } else {
                repeatCount++;
            }
        } else {
            lastCallKey = key;
            repeatCount = 1;
        }
        if (repeatCount >= maxRepeatCalls) {
            trip("repeat-calls",
                    "工具 [" + toolCode + "] 以相同参数连续调用 " + repeatCount + " 次（阈值 "
                            + maxRepeatCalls + "），疑似死循环，已熔断" + recoverySuffix());
        }
    }

    /**
     * 硬熔断前置闸门：保证「先提示后熔断」的分级语义在并行场景不被绕过。
     * 同一轮内多个并行失败会把计数瞬间打满阈值，而软提示要到下一轮开头才被
     * {@link #decorateContext} 送达模型——若此时直接 trip，模型至死没见过提醒、等于硬杀 run
     * （实测 terminal-bench 中同轮 3 次 read_file 越界拒绝 + 历史 1 次即误杀）。故要求软提示
     * 至少被 decorateContext 真正送达过一轮才允许熔断；被缓下的轮次里计数继续累加，
     * 提示送达后模型仍故障则下一次 record 即正常熔断，真死循环不会逃逸。
     */
    private boolean canTrip() {
        return adviceDelivered >= 1;
    }

    /**
     * 把本轮累积的纠偏软提示追加到上下文副本的 system prompt（只改副本，不动事件真相源），
     * 与 {@code PlanModePolicy.decorateContext} / {@code VerifyPolicy.decorateContext} 同范式；
     * 两条运行时每轮各调一次。一次性提示：consume 后清空，下一轮 system prompt 从 modelContext 重建即自动消失。
     */
    public synchronized void decorateContext(List<MessageItem> callContext) {
        String advice = consumeAdvice();
        if (advice == null || callContext == null) {
            return;
        }
        adviceDelivered++;
        String directive = "\n\n# 循环护栏提醒\n" + advice;
        for (MessageItem item : callContext) {
            if (item instanceof SystemMessageItem system) {
                String content = system.getContent() == null ? "" : system.getContent();
                system.setContent(content + directive);
                return;
            }
        }
        // 兜底：没有 system prompt 就补一条
        callContext.add(0, new SystemMessageItem(directive));
    }

    private String consumeAdvice() {
        if (pendingAdvice.length() == 0) {
            return null;
        }
        String advice = pendingAdvice.toString();
        pendingAdvice.setLength(0);
        return advice;
    }

    private void appendAdvice(String advice) {
        if (pendingAdvice.length() > 0) {
            pendingAdvice.append("\n");
        }
        pendingAdvice.append(advice);
        log.warn("[LoopGuard] advise: {}", advice);
    }

    private String globalAdviceText() {
        String head = "你已连续 " + consecutiveFailures + " 次工具调用故障。";
        if (lastFaultKind == FaultKind.TOOL_FATAL) {
            return head + "这些失败多属参数错误 / 权限拦截 / 工具不存在类问题，原样重试不会成功——"
                    + "请修正参数、确认工具名，或改用其他工具与方法，不要重复同一无效调用。";
        }
        return head + "请停止重复相同操作，先分析失败根因（读取错误输出、检查前置条件与依赖），再改变策略重试。";
    }

    /**
     * 熔断原因后缀：体现「已经尝试过的恢复动作」（对标主流分级恢复的 L3——暴露时附带已试手段）
     */
    private String recoverySuffix() {
        boolean advised = issuedGlobalAdvice || !advisedTools.isEmpty();
        return advised ? "（此前已提示模型改变策略但未见成效）" : "";
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
     * 故障分类：把工具返回/异常映射到 {@link FaultKind}。前缀与运行时/工具层已知错误标记保持一致，
     * 关键区分在于——命令业务失败（exitCode!=0）单独归为 LOGICAL，不计入连续故障，避免误杀测试-修复循环。
     */
    private FaultKind classify(String result, boolean threw) {
        if (threw) {
            // 执行抛异常：偏瞬时故障
            return FaultKind.TOOL_TRANSIENT;
        }
        if (result == null || result.isBlank()) {
            return FaultKind.NONE;
        }
        // 不可重试的工具级故障：原样重试无意义，须改输入或换方法
        if (result.contains("被安全策略拦截")
                || result.contains("参数错误")
                || result.contains("参数解析失败")
                || result.contains("tool not found")
                || result.contains("未能拼出可执行命令")
                || result.contains("arguments 不是合法 JSON")) {
            return FaultKind.TOOL_FATAL;
        }
        // 偏瞬时的工具故障（超时/连接/RPC/执行异常/MCP 业务错误）
        if (result.contains("执行超时")
                || result.contains("execute failed")
                || result.contains("timeout")
                || result.contains("not connected")
                || result.contains("rpc error")
                || result.contains("mcp tool error")) {
            return FaultKind.TOOL_TRANSIENT;
        }
        // 命令执行了但业务失败（编译/测试没过）：宝贵的反馈信号，不计入连续故障
        if (result.matches("(?s).*exitCode=[1-9]\\d*.*")) {
            return FaultKind.LOGICAL;
        }
        return FaultKind.NONE;
    }
}
