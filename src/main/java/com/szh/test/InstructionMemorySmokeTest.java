package com.szh.test;

import com.szh.agent.subagent.SubAgentType;
import com.szh.context.InstructionMemoryLoader;
import com.szh.utils.ConfigUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * @author demussong
 * @describe 分层指令记忆（L0 常驻 / L1 按需 / L2 条件化）冒烟测试：不依赖真实模型，在临时目录里搭出
 * 「全局层 + 仓库根 + 仓库内子目录 + 无关目录」的目录链，覆盖
 * 「装配顺序 → front-matter 剥离 → memory-bound 归属绑定 → 排除子树 → 项目层开关 → 按角色跳过 →
 * {@code @path} 递归与循环保护 → 异常/空输入降级 → 基础 prompt 前缀稳定（prompt cache 依赖）→ token 估算 →
 * 子 agent 类型是否携带项目记忆」。
 * <p>
 * 用法：{@code mvn -q compile && java -cp target/classes com.szh.test.InstructionMemorySmokeTest}
 * @date 2026/10/15
 */
public class InstructionMemorySmokeTest {

    private static int passed = 0;
    private static int failed = 0;

    private static Path root;

    public static void main(String[] args) throws Exception {
        try {
            setUp();
            testAssemblyOrderAndFrontMatterStripped();
            testExcludeSubtree();
            testExcludeIsConfigurable();
            testMemoryBoundGate();
            testNoFrontMatterStillLoads();
            testProjectSwitchAndRoleTrimming();
            testMasterSwitchFallsBackToBase();
            testImportExpansionAndCycleGuard();
            testNullWorkspaceAndBasePrefix();
            testTokenEstimateAndDescribe();
            testSubAgentCarryProjectMemory();

            System.out.println();
            System.out.println("========================================");
            System.out.println(" InstructionMemorySmokeTest: PASS=" + passed + " FAIL=" + failed);
            System.out.println("========================================");
            if (failed > 0) {
                System.exit(1);
            }
        } finally {
            tearDown();
        }
    }

    /**
     * 临时目录布局：
     * <pre>
     * root/global/AGENTS.md            全局层（GLOBAL-LEVEL）
     * root/repo/pom.xml                仓库 marker
     * root/repo/AGENTS.md              项目层（memory-bound: pom.xml，正文 REPO-LEVEL）
     * root/repo/eval/tb/               评测任务工作区（默认排除子树命中）
     * root/repo/src/                   正常开发工作区
     * root/bound/AGENTS.md             memory-bound 指向不存在的 marker → 应被跳过
     * root/plain/AGENTS.md             无 front-matter → 应照常加载（向后兼容）
     * </pre>
     */
    private static void setUp() throws IOException {
        root = Files.createTempDirectory("mem-smoke");
        write(root.resolve("global/AGENTS.md"), "# global\nGLOBAL-LEVEL\n");
        write(root.resolve("repo/pom.xml"), "<project/>");
        write(root.resolve("repo/AGENTS.md"), """
                ---
                memory-bound: pom.xml
                ---
                # repo
                REPO-LEVEL
                """);
        Files.createDirectories(root.resolve("repo/eval/tb"));
        Files.createDirectories(root.resolve("repo/src"));
        write(root.resolve("bound/AGENTS.md"), """
                ---
                memory-bound: surely-not-existing-marker.txt
                ---
                BOUND-MISS
                """);
        write(root.resolve("plain/AGENTS.md"), "PLAIN-LEVEL\n");

        System.setProperty("memory.instruction.enabled", "true");
        System.setProperty("memory.instruction.project.enabled", "true");
        System.setProperty("memory.instruction.project.exclude", "eval");
        System.setProperty("memory.instruction.globalDir", abs("global"));
        ConfigUtil.clearCache();
    }

    private static void tearDown() {
        System.clearProperty("memory.instruction.enabled");
        System.clearProperty("memory.instruction.project.enabled");
        System.clearProperty("memory.instruction.project.exclude");
        System.clearProperty("memory.instruction.globalDir");
        ConfigUtil.clearCache();
        if (root != null) {
            try (Stream<Path> stream = Files.walk(root)) {
                stream.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // 临时目录清理失败不影响断言结果
                    }
                });
            } catch (IOException ignored) {
                // 同上
            }
        }
    }

    /**
     * 装配顺序（全局在前、项目在后）与 front-matter 剥离（只用于闸门判定，不进 prompt）。
     */
    private static void testAssemblyOrderAndFrontMatterStripped() {
        InstructionMemoryLoader.Assembly a = InstructionMemoryLoader.assemble(path("repo/src"), true);
        assertTrue("全局层已纳入", containsSource(a, "global/AGENTS.md"));
        assertTrue("项目层已纳入", containsSource(a, "repo/AGENTS.md"));
        assertTrue("prompt 含全局正文", a.prompt().contains("GLOBAL-LEVEL"));
        assertTrue("prompt 含项目正文", a.prompt().contains("REPO-LEVEL"));
        assertTrue("全局正文排在项目正文之前",
                a.prompt().indexOf("GLOBAL-LEVEL") < a.prompt().indexOf("REPO-LEVEL"));
        assertTrue("front-matter 键未被送进 prompt", !a.prompt().contains("memory-bound"));
        assertTrue("scope 标记齐全", a.prompt().contains("[scope=global") && a.prompt().contains("[scope=project"));
    }

    /**
     * 排除子树：workspace 在仓库内的 {@code eval/**} 下时，仓库根的项目层不该进来
     * （评测/产物目录里跑的是与开发本仓库无关的任务）。
     */
    private static void testExcludeSubtree() {
        InstructionMemoryLoader.Assembly a = InstructionMemoryLoader.assemble(path("repo/eval/tb"), true);
        assertTrue("全局层不受排除子树影响", containsSource(a, "global/AGENTS.md"));
        assertTrue("仓库项目层被跳过", !containsSource(a, "repo/AGENTS.md"));
        assertTrue("prompt 不含项目正文", !a.prompt().contains("REPO-LEVEL"));
        assertTrue("跳过原因写明排除子树", skippedReasonOf(a, "repo/AGENTS.md").contains("排除子树"));
    }

    /**
     * 排除项可配：换成不相干的 token 后同一 workspace 应正常加载——证明闸门确实由配置驱动。
     */
    private static void testExcludeIsConfigurable() {
        System.setProperty("memory.instruction.project.exclude", "no-such-dir");
        try {
            InstructionMemoryLoader.Assembly a = InstructionMemoryLoader.assemble(path("repo/eval/tb"), true);
            assertTrue("改排除项后项目层恢复", containsSource(a, "repo/AGENTS.md"));
        } finally {
            System.setProperty("memory.instruction.project.exclude", "eval");
        }
        // 排除匹配按路径元素对齐，不能因前缀相似误伤（"ev" 不该命中 "eval"）
        System.setProperty("memory.instruction.project.exclude", "ev");
        try {
            assertTrue("单级排除项不做子串误伤",
                    InstructionMemoryLoader.assemble(path("repo/eval/tb"), true).prompt().contains("REPO-LEVEL"));
        } finally {
            System.setProperty("memory.instruction.project.exclude", "eval");
        }
    }

    /**
     * memory-bound 归属绑定：声明的 marker 在文件所在目录解析不到，就不认这笔项目记忆属于当前工作区。
     */
    private static void testMemoryBoundGate() {
        InstructionMemoryLoader.Assembly a = InstructionMemoryLoader.assemble(path("bound"), true);
        assertTrue("bound 层被跳过", !containsSource(a, "bound/AGENTS.md"));
        assertTrue("跳过原因写明 marker 未命中", skippedReasonOf(a, "bound/AGENTS.md").contains("marker"));
        assertTrue("prompt 不含 bound 正文", !a.prompt().contains("BOUND-MISS"));
    }

    /**
     * 向后兼容：没有 front-matter 的 AGENTS.md 一律照常加载（闸门默认放行，不新增强制声明）。
     */
    private static void testNoFrontMatterStillLoads() {
        InstructionMemoryLoader.Assembly a = InstructionMemoryLoader.assemble(path("plain"), true);
        assertTrue("无 front-matter 仍加载", containsSource(a, "plain/AGENTS.md"));
        assertTrue("prompt 含 plain 正文", a.prompt().contains("PLAIN-LEVEL"));
    }

    /**
     * 项目层两种关法：调用方显式 projectMemory=false（子 agent 按类型裁剪）与配置开关，
     * 都只关项目层、保留全局层。
     */
    private static void testProjectSwitchAndRoleTrimming() {
        InstructionMemoryLoader.Assembly offByCaller = InstructionMemoryLoader.assemble(path("repo/src"), false);
        assertTrue("调用方关掉项目层", !containsSource(offByCaller, "repo/AGENTS.md"));
        assertTrue("全局层仍在", containsSource(offByCaller, "global/AGENTS.md"));
        assertTrue("跳过原因写明 projectMemory", skippedReasonOf(offByCaller, "repo/AGENTS.md").contains("projectMemory"));
        assertTrue("loadSystemPrompt(workspace,false) 不含项目正文",
                !InstructionMemoryLoader.loadSystemPrompt(path("repo/src"), false).contains("REPO-LEVEL"));
        assertTrue("loadSystemPrompt(workspace,true) 含项目正文",
                InstructionMemoryLoader.loadSystemPrompt(path("repo/src"), true).contains("REPO-LEVEL"));

        System.setProperty("memory.instruction.project.enabled", "false");
        try {
            assertTrue("配置关掉项目层", !InstructionMemoryLoader.loadSystemPrompt(path("repo/src")).contains("REPO-LEVEL"));
        } finally {
            System.setProperty("memory.instruction.project.enabled", "true");
        }
    }

    /**
     * 总开关：关闭后连全局层也不要，只剩内置基础 prompt。
     */
    private static void testMasterSwitchFallsBackToBase() {
        System.setProperty("memory.instruction.enabled", "false");
        try {
            InstructionMemoryLoader.Assembly a = InstructionMemoryLoader.assemble(path("repo/src"), true);
            assertTrue("总开关关闭回退 BASE", a.prompt().equals(InstructionMemoryLoader.BASE_SYSTEM_PROMPT));
            assertEquals("只剩一条 baseline", 1, a.layers().size());
            assertTrue("baseline 记录降级原因", a.layers().get(0).reason().contains("memory.instruction.enabled"));
        } finally {
            System.setProperty("memory.instruction.enabled", "true");
        }
    }

    /**
     * {@code @path} 引入仍然有效（递归展开），且自引用被 visited 集合挡住不会无限展开。
     */
    private static void testImportExpansionAndCycleGuard() throws IOException {
        write(root.resolve("repo/imported.md"), "IMPORTED-BODY\n");
        write(root.resolve("cycle/imported.md"), "IMPORTED-BODY\n");
        // 自引用：AGENTS.md 引入自己 → visited 集合必须挡住，正文只出现一次且不栈溢出
        write(root.resolve("cycle/AGENTS.md"), """
                ---
                memory-bound: ../repo/pom.xml
                ---
                CYCLE-HEAD
                @imported.md
                @AGENTS.md
                """);

        String prompt = InstructionMemoryLoader.loadSystemPrompt(path("cycle"), true);
        assertTrue("@path 已展开", prompt.contains("IMPORTED-BODY"));
        assertTrue("正文保留", prompt.contains("CYCLE-HEAD"));
        assertEquals("自引用只展开一次", 1, countOccurrences(prompt, "CYCLE-HEAD"));
    }

    /**
     * 稳健性：workspace 为 null 不抛异常；基础 prompt 始终在最前（前缀稳定 = prompt cache 可命中）。
     */
    private static void testNullWorkspaceAndBasePrefix() {
        InstructionMemoryLoader.Assembly a = InstructionMemoryLoader.assemble(null, true);
        assertTrue("workspace=null 仍有全局层", a.prompt().contains("GLOBAL-LEVEL"));
        assertTrue("基础 prompt 前缀稳定", a.prompt().startsWith(InstructionMemoryLoader.BASE_SYSTEM_PROMPT));
        assertTrue("纳入数与跳过数自洽",
                a.includedCount() + a.skippedCount() == a.layers().size());
    }

    /**
     * token 估算与 {@code /memory} 展示文本。
     */
    private static void testTokenEstimateAndDescribe() {
        assertEquals("空串估算为 0", 0, InstructionMemoryLoader.estimateTokens(""));
        assertTrue("中文按字计", InstructionMemoryLoader.estimateTokens("你好世界") >= 4);
        assertEquals("ASCII 按 0.3 折算取整", 2, InstructionMemoryLoader.estimateTokens("abcd"));

        InstructionMemoryLoader.Assembly a = InstructionMemoryLoader.assemble(path("repo/eval/tb"), true);
        String included = a.layers().stream().filter(InstructionMemoryLoader.Layer::included)
                .findFirst().orElseThrow().describe();
        String skipped = a.layers().stream().filter(l -> !l.included())
                .findFirst().orElseThrow().describe();
        assertTrue("已纳入行带勾与字符数", included.contains("[✓]") && included.contains("字符="));
        assertTrue("跳过行带叉与原因", skipped.contains("[×]") && skipped.contains("原因="));
    }

    /**
     * 子 agent 按类型决定是否携带项目记忆：纯检索型不带（派生 N 个不多付 N 份），
     * 需要对照宿主约定改代码/审代码的才带。
     */
    private static void testSubAgentCarryProjectMemory() {
        assertTrue("general-purpose 携带", SubAgentType.GENERAL_PURPOSE.carryProjectMemory());
        assertTrue("code-review 携带（要对照宿主约定）", SubAgentType.CODE_REVIEW.carryProjectMemory());
        assertTrue("explore 不携带", !SubAgentType.EXPLORE.carryProjectMemory());
        assertEquals("未知类型回退 general-purpose", SubAgentType.GENERAL_PURPOSE, SubAgentType.from("whatever"));
    }

    // ---------- 小工具 ----------

    private static boolean containsSource(InstructionMemoryLoader.Assembly a, String relativePath) {
        Path target = root.resolve(relativePath).toAbsolutePath().normalize();
        return a.layers().stream().anyMatch(l -> l.included() && l.source() != null
                && l.source().toAbsolutePath().normalize().equals(target));
    }

    private static String skippedReasonOf(InstructionMemoryLoader.Assembly a, String relativePath) {
        Path target = root.resolve(relativePath).toAbsolutePath().normalize();
        List<InstructionMemoryLoader.Layer> hits = a.layers().stream()
                .filter(l -> l.source() != null && l.source().toAbsolutePath().normalize().equals(target))
                .toList();
        return hits.isEmpty() ? "" : String.join(" | ", hits.stream()
                .map(l -> String.valueOf(l.reason())).toList());
    }

    private static int countOccurrences(String text, String token) {
        int count = 0;
        int idx = text.indexOf(token);
        while (idx >= 0) {
            count++;
            idx = text.indexOf(token, idx + token.length());
        }
        return count;
    }

    private static Path path(String relative) {
        return root.resolve(relative).toAbsolutePath().normalize();
    }

    private static String abs(String relative) {
        return path(relative).toString();
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
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
