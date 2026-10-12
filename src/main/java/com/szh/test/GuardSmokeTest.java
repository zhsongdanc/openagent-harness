package com.szh.test;

import com.szh.agent.LoopGuard;
import com.szh.context.dto.MessageItem;
import com.szh.context.dto.SystemMessageItem;
import com.szh.utils.ConfigUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * @author demussong
 * @describe 循环护栏（LoopGuard）冒烟测试：不依赖真实模型与工具执行，直接喂构造好的调用结果，
 * 跑通「故障分类 → 分级升级（软提示/硬熔断）→ 按工具路径独立计数 → 重复调用检测 → fix-then-retry 豁免」。
 * <p>
 * 重点验证 coding agent 的两条关键语义：
 * <ol>
 *   <li>命令业务失败（exitCode!=0）不计入连续故障——「测试-修复」循环不会被误杀；</li>
 *   <li>改过代码后重跑相同命令属 fix-then-retry——凭「进展信用」豁免重复调用熔断。</li>
 * </ol>
 * 用法：{@code mvn compile && java -cp target/classes:... com.szh.test.GuardSmokeTest}
 * @date 2026/10/12
 */
public class GuardSmokeTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        ConfigUtil.clearCache();
        try {
            testLogicalFailureNotCounted();
            testTestFixLoopSurvives();
            testPerToolTrip();
            testGlobalConsecutiveTrip();
            testRepeatTrip();
            testFixThenRetryCredit();
            testAdviseInjection();
            testDisabled();

            System.out.println();
            System.out.println("========================================");
            System.out.println(" GuardSmokeTest: PASS=" + passed + " FAIL=" + failed);
            System.out.println("========================================");
            if (failed > 0) {
                System.exit(1);
            }
        } finally {
            ConfigUtil.clearCache();
        }
    }

    /**
     * 命令业务失败（exitCode!=0）是测试-修复循环的正常反馈，不计入连续故障，不应熔断。
     * 用不同参数避免触发重复调用检测，专验「逻辑失败不计数」。
     */
    private static void testLogicalFailureNotCounted() {
        LoopGuard guard = new LoopGuard();
        for (int i = 0; i < 10; i++) {
            guard.record("shell", "{\"cmd\":\"mvn test " + i + "\"}", "exitCode=1", false);
        }
        assertTrue("exitCode!=0 不计入连续故障，不熔断", !guard.isTripped());
    }

    /**
     * 典型「测试-修复」循环：跑测试失败 -> 改代码成功 -> 再跑相同测试失败 …… 交替推进，不应被误杀。
     */
    private static void testTestFixLoopSurvives() {
        LoopGuard guard = new LoopGuard();
        for (int i = 0; i < 6; i++) {
            guard.record("shell", "{\"cmd\":\"mvn test\"}", "exitCode=1", false);
            guard.record("edit_file", "{\"path\":\"A.java\",\"i\":" + i + "}", "ok", false);
        }
        assertTrue("测试-修复循环不熔断", !guard.isTripped());
    }

    /**
     * 单工具路径独立熔断：同一工具连续故障达 maxFailuresPerTool(4)，用不同参数避开重复检测。
     */
    private static void testPerToolTrip() {
        LoopGuard guard = new LoopGuard();
        for (int i = 0; i < 4; i++) {
            guard.record("read_file", "{\"p\":" + i + "}", "tool not found: read_file", false);
        }
        assertTrue("单工具连续故障应熔断", guard.isTripped());
        assertEquals("熔断类型", "tool-failures", guard.getViolation().getType());
        assertTrue("熔断原因含工具名", guard.getViolation().getReason().contains("read_file"));
        assertTrue("熔断原因附已尝试的恢复动作",
                guard.getViolation().getReason().contains("已提示模型改变策略"));
    }

    /**
     * 全局路径熔断：不同工具交替故障，避开单工具阈值，累积到全局 maxConsecutiveFailures(5) 熔断。
     */
    private static void testGlobalConsecutiveTrip() {
        LoopGuard guard = new LoopGuard();
        for (String t : new String[]{"t1", "t2", "t3", "t4", "t5"}) {
            guard.record(t, "{}", "execute failed: boom", false);
        }
        assertTrue("全局连续故障应熔断", guard.isTripped());
        assertEquals("熔断类型", "consecutive-failures", guard.getViolation().getType());
    }

    /**
     * 重复调用检测：成功结果但完全相同的调用连续重复达 maxRepeatCalls(4) -> 死循环熔断。
     */
    private static void testRepeatTrip() {
        LoopGuard guard = new LoopGuard();
        for (int i = 0; i < 4; i++) {
            guard.record("shell", "{\"cmd\":\"ls\"}", "ok", false);
        }
        assertTrue("重复调用应熔断", guard.isTripped());
        assertEquals("熔断类型", "repeat-calls", guard.getViolation().getType());
    }

    /**
     * fix-then-retry 豁免：中途有成功的写入类工具（授予进展信用）时，相同命令的重跑被豁免一次，
     * 需要更多次连续重复才熔断；对照组无进展信用则按阈值正常熔断。
     */
    private static void testFixThenRetryCredit() {
        // 对照组：无进展信用，相同调用连续 4 次熔断
        LoopGuard g1 = new LoopGuard();
        for (int i = 0; i < 3; i++) {
            g1.record("shell", "{\"cmd\":\"mvn test\"}", "ok", false);
        }
        assertTrue("对照组：3 次相同调用未达阈值", !g1.isTripped());
        g1.record("shell", "{\"cmd\":\"mvn test\"}", "ok", false);
        assertTrue("对照组：第 4 次相同调用熔断", g1.isTripped());

        // 实验组：先改代码授予进展信用，随后相同命令重跑被豁免一次，4 次仍不熔断
        LoopGuard g2 = new LoopGuard();
        g2.record("edit_file", "{\"path\":\"A.java\"}", "ok", false);
        for (int i = 0; i < 4; i++) {
            g2.record("shell", "{\"cmd\":\"mvn test\"}", "ok", false);
        }
        assertTrue("fix-then-retry：改代码后重跑相同命令被豁免，未熔断", !g2.isTripped());
    }

    /**
     * 分级升级：硬熔断前先注入软提示（往 system prompt 副本追加纠偏指令），且为一次性提示。
     */
    private static void testAdviseInjection() {
        LoopGuard guard = new LoopGuard();
        // 三个不同工具各故障一次：全局连续故障达 adviseAfterFailures(3)，触发软提示但未硬熔断
        // 让触发软提示的最后一次为不可重试故障（TOOL_FATAL），以覆盖其定制文案分支
        guard.record("read_file", "{\"p\":1}", "execute failed: boom", false);
        guard.record("grep", "{\"q\":2}", "执行超时", false);
        guard.record("find", "{\"n\":3}", "tool not found: find", false);
        assertTrue("软提示阶段未熔断", !guard.isTripped());

        List<MessageItem> ctx = new ArrayList<>();
        ctx.add(new SystemMessageItem("你是编码助手"));
        guard.decorateContext(ctx);
        String content = ((SystemMessageItem) ctx.get(0)).getContent();
        assertTrue("注入了循环护栏提醒", content.contains("循环护栏提醒"));
        assertTrue("保留了原 system 内容", content.contains("你是编码助手"));
        assertTrue("不可重试故障的纠偏文案点名参数/权限/工具", content.contains("参数错误"));

        // 一次性：consume 后再 decorate 不重复注入
        List<MessageItem> ctx2 = new ArrayList<>();
        ctx2.add(new SystemMessageItem("fresh"));
        guard.decorateContext(ctx2);
        assertTrue("软提示一次性，不重复注入",
                !((SystemMessageItem) ctx2.get(0)).getContent().contains("循环护栏提醒"));
    }

    /**
     * 总开关关闭后完全不熔断。
     */
    private static void testDisabled() {
        System.setProperty("agent.guard.enabled", "false");
        ConfigUtil.clearCache();
        try {
            LoopGuard guard = new LoopGuard();
            for (int i = 0; i < 10; i++) {
                guard.record("shell", "{\"cmd\":\"ls\"}", "ok", false);
            }
            assertTrue("关闭护栏后不熔断", !guard.isTripped());
        } finally {
            System.clearProperty("agent.guard.enabled");
            ConfigUtil.clearCache();
        }
    }

    private static void assertTrue(String label, boolean cond) {
        if (cond) {
            passed++;
            System.out.println("[PASS] " + label);
        } else {
            failed++;
            System.out.println("[FAIL] " + label);
        }
    }

    private static void assertEquals(String label, Object expected, Object actual) {
        boolean eq = expected == null ? actual == null : expected.equals(actual);
        if (eq) {
            passed++;
            System.out.println("[PASS] " + label + " = " + actual);
        } else {
            failed++;
            System.out.println("[FAIL] " + label + " expected=" + expected + " actual=" + actual);
        }
    }
}
