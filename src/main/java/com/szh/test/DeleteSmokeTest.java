package com.szh.test;

import com.szh.tool.ToolContext;
import com.szh.tool.ToolRegistry;
import com.szh.tool.tools.file.DeleteFileTool;
import com.szh.tool.tools.meta.AgentMode;
import com.szh.tool.tools.meta.AgentModeStore;
import com.szh.tool.tools.meta.ConsoleQuestionPrompter;
import com.szh.tool.tools.meta.Question;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * delete_file 冒烟验证（main 直跑，注入 Fake 问询器，不依赖真实 stdin / 模型 API）。
 * 覆盖工具层三道硬限制与确认旋钮：
 * <ol>
 *   <li>repo root 边界：工作区外路径拒绝删除；</li>
 *   <li>禁止目录删除；</li>
 *   <li>敏感删除确认：.java 后缀需确认（同意才删、取消则保留）；</li>
 *   <li>豁免规则：test 目录 / XxxTest.java 测试文件无需确认直接删；</li>
 *   <li>大小阈值：超 minBytes 的普通文件需确认；</li>
 *   <li>非敏感小文件：无需确认直接删；</li>
 *   <li>非交互（EOF）按 autoApprove 兜底：false 拒绝、true 放行；</li>
 *   <li>注册与 PLAN 拒绝名单联动。</li>
 * </ol>
 *
 * @author demussong
 * @date 2026/10/14
 */
@Slf4j
public class DeleteSmokeTest {

    private static final AtomicInteger PASSED = new AtomicInteger();
    private static final AtomicInteger FAILED = new AtomicInteger();

    public static void main(String[] args) throws Exception {
        System.setProperty("store.engine", "MEMORY");
        System.setProperty("memory.enabled", "false");
        System.setProperty("memory.tools.enabled", "false");
        System.setProperty("shell.security.enabled", "false");
        // 用测试专用豁免正则（去掉 tmp/target），避免工作区恰好建在 /tmp 下时把所有文件都误判为豁免
        System.setProperty("file.delete.confirm.exempt.regex",
                "(?:^|/)(?:test|tests)(?:/|$)|(?:Test|Tests|TestCase|Spec|IT)\\.java$");
        System.setProperty("file.delete.confirm.suffixes", ".java,pom.xml");
        System.setProperty("file.delete.confirm.minBytes", "10240");

        Path workspace = Files.createTempDirectory("delete-smoke");
        log.info("冒烟工作区: {}", workspace);

        testHardLimits(workspace);
        testConfirmByAnswer(workspace);
        testExempt(workspace);
        testSizeThreshold(workspace);
        testNonSensitive(workspace);
        testNonInteractiveFallback(workspace);
        testRegistryAndPlanMode();

        log.info("====== delete_file 冒烟结果: passed={}, failed={} ======", PASSED.get(), FAILED.get());
        if (FAILED.get() > 0) {
            System.exit(1);
        }
    }

    /**
     * 硬限制：repo root 之外拒绝、目录拒绝、不存在拒绝
     */
    private static void testHardLimits(Path workspace) throws Exception {
        DeleteFileTool del = new DeleteFileTool(new FakePrompter("确认删除"));

        Path outside = workspace.getParent().resolve("delete-smoke-outside.txt");
        Files.writeString(outside, "secret");
        String outsideRes = del.execute(ctx(workspace, "{\"path\":\"" + outside + "\"}"));
        check("拒绝删除 repo root 之外的文件", outsideRes.contains("参数错误")
                && outsideRes.contains("之外") && Files.exists(outside));
        Files.deleteIfExists(outside);

        Path dir = workspace.resolve("src");
        Files.createDirectories(dir);
        String dirRes = del.execute(ctx(workspace, "{\"path\":\"src\"}"));
        check("拒绝删除目录", dirRes.contains("参数错误") && dirRes.contains("禁止删除目录")
                && Files.isDirectory(dir));

        String missingRes = del.execute(ctx(workspace, "{\"path\":\"nope.txt\"}"));
        check("删除不存在文件报错", missingRes.contains("参数错误") && missingRes.contains("不存在"));
    }

    /**
     * .java 命中敏感后缀需确认：同意才删、取消则保留
     */
    private static void testConfirmByAnswer(Path workspace) throws Exception {
        Files.createDirectories(workspace.resolve("src"));
        Path main = workspace.resolve("src/Main.java");
        Files.writeString(main, "class Main {}");

        DeleteFileTool cancel = new DeleteFileTool(new FakePrompter("取消"));
        String cancelRes = cancel.execute(ctx(workspace, "{\"path\":\"src/Main.java\"}"));
        check("用户取消时保留 .java 文件", cancelRes.contains("已取消") && Files.exists(main));

        DeleteFileTool ok = new DeleteFileTool(new FakePrompter("确认删除"));
        String okRes = ok.execute(ctx(workspace, "{\"path\":\"src/Main.java\"}"));
        check("用户同意时删除 .java 文件", okRes.contains("已删除") && !Files.exists(main));
    }

    /**
     * 豁免规则：test 目录下的 .java 与 XxxTest.java 无需确认（注入「取消」问询器仍应删除，证明未走确认）
     */
    private static void testExempt(Path workspace) throws Exception {
        Path testDir = workspace.resolve("src/test");
        Files.createDirectories(testDir);
        Path inTestDir = testDir.resolve("Helper.java");
        Files.writeString(inTestDir, "class Helper {}");
        Path testFile = workspace.resolve("src/FooTest.java");
        Files.writeString(testFile, "class FooTest {}");

        DeleteFileTool del = new DeleteFileTool(new FakePrompter("取消"));
        String r1 = del.execute(ctx(workspace, "{\"path\":\"src/test/Helper.java\"}"));
        check("test 目录下的 .java 豁免确认直接删", r1.contains("已删除") && !Files.exists(inTestDir));
        String r2 = del.execute(ctx(workspace, "{\"path\":\"src/FooTest.java\"}"));
        check("XxxTest.java 测试文件豁免确认直接删", r2.contains("已删除") && !Files.exists(testFile));
    }

    /**
     * 大小阈值：超 minBytes 的普通（非敏感后缀）文件需确认
     */
    private static void testSizeThreshold(Path workspace) throws Exception {
        Path big = workspace.resolve("dump.dat");
        Files.writeString(big, "x".repeat(11000));

        DeleteFileTool cancel = new DeleteFileTool(new FakePrompter("取消"));
        String res = cancel.execute(ctx(workspace, "{\"path\":\"dump.dat\"}"));
        check("超大小阈值的文件需确认（取消则保留）", res.contains("已取消") && Files.exists(big));
        Files.deleteIfExists(big);
    }

    /**
     * 非敏感小文件：无需确认直接删（注入「取消」问询器仍应删除）
     */
    private static void testNonSensitive(Path workspace) throws Exception {
        Path note = workspace.resolve("notes.txt");
        Files.writeString(note, "hello");

        DeleteFileTool del = new DeleteFileTool(new FakePrompter("取消"));
        String res = del.execute(ctx(workspace, "{\"path\":\"notes.txt\"}"));
        check("非敏感小文件无需确认直接删", res.contains("已删除") && !Files.exists(note));
    }

    /**
     * 非交互（问询器返回 null）按 autoApprove 兜底：默认 false 拒绝、显式 true 放行
     */
    private static void testNonInteractiveFallback(Path workspace) throws Exception {
        Path target = workspace.resolve("App.java");

        Files.writeString(target, "class App {}");
        System.setProperty("file.delete.confirm.autoApprove", "false");
        DeleteFileTool deny = new DeleteFileTool(new FakePrompter(null));
        String denyRes = deny.execute(ctx(workspace, "{\"path\":\"App.java\"}"));
        check("非交互 autoApprove=false 时拒绝删除", denyRes.contains("已取消") && Files.exists(target));

        System.setProperty("file.delete.confirm.autoApprove", "true");
        DeleteFileTool allow = new DeleteFileTool(new FakePrompter(null));
        String allowRes = allow.execute(ctx(workspace, "{\"path\":\"App.java\"}"));
        check("非交互 autoApprove=true 时放行删除", allowRes.contains("已删除") && !Files.exists(target));
        System.clearProperty("file.delete.confirm.autoApprove");
    }

    /**
     * 注册进 ToolRegistry；PLAN 模式拒绝名单剔除 delete_file
     */
    private static void testRegistryAndPlanMode() {
        ToolRegistry registry = new ToolRegistry();
        check("ToolRegistry 注册了 delete_file", registry.getToolByCode(DeleteFileTool.CODE) != null);

        String session = "delete-plan-smoke-session";
        AgentModeStore.get().set(session, AgentMode.PLAN);
        boolean removedInPlan = com.szh.tool.tools.meta.PlanModePolicy
                .effectiveTools(registry.getTools(), session)
                .stream().noneMatch(t -> t.getCode().equals(DeleteFileTool.CODE));
        check("PLAN 模式剔除 delete_file", removedInPlan);

        AgentModeStore.get().set(session, AgentMode.NORMAL);
        boolean keptInNormal = com.szh.tool.tools.meta.PlanModePolicy
                .effectiveTools(registry.getTools(), session)
                .stream().anyMatch(t -> t.getCode().equals(DeleteFileTool.CODE));
        check("NORMAL 模式保留 delete_file", keptInNormal);
        AgentModeStore.get().clear(session);
    }

    private static ToolContext ctx(Path workspace, String args) {
        return new ToolContext("smoke-session", "smoke-run", workspace.toString(), args);
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            PASSED.incrementAndGet();
            log.info("[PASS] {}", name);
        } else {
            FAILED.incrementAndGet();
            log.error("[FAIL] {}", name);
        }
    }

    /**
     * 伪问询器：返回固定应答（null 模拟非交互 EOF），不读真实 stdin
     */
    private static class FakePrompter extends ConsoleQuestionPrompter {
        private final String answer;

        FakePrompter(String answer) {
            this.answer = answer;
        }

        @Override
        public synchronized List<String> ask(List<Question> questions) {
            return answer == null ? null : List.of(answer);
        }
    }
}
