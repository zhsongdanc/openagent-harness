package com.szh.test;

import com.szh.agent.verify.VerifyConfig;
import com.szh.agent.verify.VerifyConfigLoader;
import com.szh.agent.verify.VerifyGate;
import com.szh.agent.verify.VerifyResult;
import com.szh.agent.verify.Verifier;
import com.szh.utils.ConfigUtil;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * @author demussong
 * @describe 验证闭环（VerifyGate / Verifier / VerifyConfigLoader）冒烟测试：不依赖真实模型与项目构建，
 * 用 {@code true}/{@code false} 这类零副作用命令跑通「武装判定 → fail-fast 执行 → 失败回灌文本 →
 * onlyWhenDirty 脏标记 → maxFixRounds 计数边界 → verify.json 分层加载与 ~/${VAR} 展开」。
 * <p>
 * 执行期间关闭 shell 安全闸门（{@code shell.security.enabled=false}），使命令直接执行、不触发人工确认与沙箱包装，
 * 保证结果确定、非交互。用法：{@code mvn compile && java -cp target/classes:... com.szh.test.VerifySmokeTest}
 * @date 2026/10/9
 */
public class VerifySmokeTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        // 关闭安全闸门：让 true/false 直接执行，绕过人工确认与沙箱包装，保证测试确定且非交互
        System.setProperty("shell.security.enabled", "false");
        ConfigUtil.clearCache();
        try {
            testGateFailFastAndFeedback();
            testPassAndDirty();
            testArmed();
            testRetryBounds();
            testConfigLoader();

            System.out.println();
            System.out.println("========================================");
            System.out.println(" VerifySmokeTest: PASS=" + passed + " FAIL=" + failed);
            System.out.println("========================================");
            if (failed > 0) {
                System.exit(1);
            }
        } finally {
            System.clearProperty("shell.security.enabled");
            ConfigUtil.clearCache();
        }
    }

    /**
     * fail-fast + 失败结果 + 回灌文本：命令 ["false"] 退出码 1，验证未通过并生成含失败命令的修复指令。
     */
    private static void testGateFailFastAndFeedback() {
        VerifyConfig cfg = base(true, false, List.of("false"));
        VerifyGate gate = new VerifyGate(null, cfg);

        assertTrue("armed with commands", gate.isArmed());
        assertTrue("shouldVerify (onlyWhenDirty off)", gate.shouldVerify());

        VerifyResult r = gate.runVerification();
        assertTrue("false -> not passed", !r.passed());
        assertEquals("failedCommand", "false", r.failedCommand());
        assertEquals("exitCode", 1, r.exitCode());

        String fb = r.feedback(1, 3);
        assertTrue("feedback has header", fb.contains("自动验证未通过 · 第 1/3 次"));
        assertTrue("feedback has failed command", fb.contains("失败命令: false"));
        assertTrue("feedback forbids finishing", fb.contains("验证通过前不要再次宣布完成"));
        assertTrue("summary mentions command", r.summary().contains("false"));

        // fail-fast：首条失败即停，后续 true 不再执行，失败命令锁定为 false
        VerifyConfig ff = base(true, false, List.of("true", "false", "true"));
        VerifyResult rff = new VerifyGate(null, ff).runVerification();
        assertTrue("fail-fast not passed", !rff.passed());
        assertEquals("fail-fast stops at false", "false", rff.failedCommand());
    }

    /**
     * 全通过 + onlyWhenDirty 脏标记语义：未改代码不验证，命中写入类工具后才验证。
     */
    private static void testPassAndDirty() {
        // 通过路径
        VerifyConfig pass = base(true, false, List.of("true"));
        VerifyResult rp = new VerifyGate(null, pass).runVerification();
        assertTrue("true -> passed", rp.passed());
        assertEquals("passed summary", "验证通过", rp.summary());

        // 空命令：无验证可做，直接判通过
        assertTrue("empty commands -> pass", Verifier.verify(base(true, false, List.of()), null).passed());

        // onlyWhenDirty：未 markDirty 时不验证；read_file 不算脏；edit_file 算脏
        VerifyConfig dirty = base(true, true, List.of("true"));
        VerifyGate gate = new VerifyGate(null, dirty);
        assertTrue("armed even when onlyWhenDirty", gate.isArmed());
        assertTrue("not dirty -> shouldVerify false", !gate.shouldVerify());
        gate.markDirty("read_file");
        assertTrue("read_file not dirty", !gate.shouldVerify());
        gate.markDirty("edit_file");
        assertTrue("edit_file -> dirty -> shouldVerify true", gate.shouldVerify());

        // 其余写入类工具同样置脏
        for (String code : List.of("write_file", "git_add", "git_commit", "git_push")) {
            VerifyGate g = new VerifyGate(null, base(true, true, List.of("true")));
            g.markDirty(code);
            assertTrue(code + " -> dirty", g.shouldVerify());
        }
    }

    /**
     * 武装判定：总开关关闭或无命令时闸门不武装。
     */
    private static void testArmed() {
        assertTrue("disabled -> not armed", !new VerifyGate(null, base(false, false, List.of("true"))).isArmed());
        assertTrue("enabled but no commands -> not armed",
                !new VerifyGate(null, base(true, false, List.of())).isArmed());
        // null 配置：退化为默认（未启用），不武装、不抛异常
        assertTrue("null config -> not armed", !new VerifyGate(null, null).isArmed());
    }

    /**
     * maxFixRounds 计数与 canRetry 边界：修满上限后不再逼修。
     */
    private static void testRetryBounds() {
        VerifyConfig cfg = base(true, false, List.of("false"));
        cfg.setMaxFixRounds(2);
        VerifyGate gate = new VerifyGate(null, cfg);

        assertEquals("initial round", 0, gate.round());
        assertEquals("maxFixRounds", 2, gate.maxFixRounds());
        assertTrue("can retry at round 0", gate.canRetry());

        gate.incrementRound();
        assertEquals("round after 1", 1, gate.round());
        assertTrue("can retry at round 1", gate.canRetry());

        gate.incrementRound();
        assertEquals("round after 2", 2, gate.round());
        assertTrue("cannot retry at max", !gate.canRetry());
    }

    /**
     * verify.json 分层加载：项目级文件覆盖 properties 基线，命令做 ~/${HOME} 展开；缺文件静默降级为未启用。
     */
    private static void testConfigLoader() throws Exception {
        String home = System.getProperty("user.home");
        Path workspace = Files.createTempDirectory("openagent-verify-smoke-");
        Path openagent = workspace.resolve(".openagent");
        Files.createDirectories(openagent);
        String json = """
                {
                  "enabled": true,
                  "maxFixRounds": 5,
                  "timeoutSeconds": 42,
                  "onlyWhenDirty": false,
                  "commands": ["echo ${HOME}", "~/bin/verify", "   ", "mvn -q test"]
                }
                """;
        Files.writeString(openagent.resolve("verify.json"), json, StandardCharsets.UTF_8);

        // 全局配置指向不存在的临时路径，确保只命中项目级 verify.json
        Path missingGlobal = workspace.resolve("does-not-exist.json");
        System.setProperty("agent.verify.config.file", missingGlobal.toAbsolutePath().toString());
        ConfigUtil.clearCache();
        try {
            VerifyConfig loaded = VerifyConfigLoader.load(workspace.toAbsolutePath().toString());
            assertTrue("json enabled overrides baseline", loaded.isEnabled());
            assertEquals("json maxFixRounds", 5, loaded.getMaxFixRounds());
            assertEquals("json timeoutSeconds", 42L, loaded.getTimeoutSeconds());
            assertTrue("json onlyWhenDirty false", !loaded.isOnlyWhenDirty());

            List<String> cmds = loaded.getCommands();
            assertEquals("blank command dropped", 3, cmds.size());
            assertEquals("${HOME} expanded", "echo " + home, cmds.get(0));
            assertEquals("~ expanded", home + "/bin/verify", cmds.get(1));
            assertEquals("plain command kept", "mvn -q test", cmds.get(2));

            // 缺文件降级：无项目级 json、全局路径也不存在 -> 命令为空、回落 properties 基线（默认未启用）
            Path emptyWorkspace = Files.createTempDirectory("openagent-verify-empty-");
            try {
                VerifyConfig fallback = VerifyConfigLoader.load(emptyWorkspace.toAbsolutePath().toString());
                assertTrue("missing json -> commands empty", fallback.getCommands().isEmpty());
                assertTrue("missing json -> not armed",
                        !new VerifyGate(emptyWorkspace.toAbsolutePath().toString(), fallback).isArmed());
            } finally {
                deleteRecursively(emptyWorkspace);
            }
        } finally {
            System.clearProperty("agent.verify.config.file");
            ConfigUtil.clearCache();
            deleteRecursively(workspace);
        }
    }

    /**
     * 构造测试用配置
     *
     * @param enabled       总开关
     * @param onlyWhenDirty 仅改过代码才验证
     * @param commands      验证命令
     */
    private static VerifyConfig base(boolean enabled, boolean onlyWhenDirty, List<String> commands) {
        VerifyConfig cfg = new VerifyConfig();
        cfg.setEnabled(enabled);
        cfg.setOnlyWhenDirty(onlyWhenDirty);
        cfg.setCommands(new ArrayList<>(commands));
        cfg.setMaxFixRounds(3);
        cfg.setTimeoutSeconds(30);
        return cfg;
    }

    private static void deleteRecursively(Path root) {
        try {
            if (!Files.exists(root)) {
                return;
            }
            try (var stream = Files.walk(root)) {
                stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignore) {
                    }
                });
            }
        } catch (Exception ignore) {
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
