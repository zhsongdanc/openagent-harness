package com.szh.test;

import com.szh.tool.BinaryProbe;
import com.szh.tool.Tool;
import com.szh.tool.ToolContext;
import com.szh.tool.ToolDefinition;
import com.szh.tool.ToolRegistry;
import com.szh.tool.tools.shell.ShellCommandTool;
import com.szh.utils.ConfigUtil;

import java.util.List;

/**
 * @author demussong
 * @describe 工具能力探测按需注册冒烟测试：不依赖真实模型，直接验证
 * <ol>
 *   <li>{@link BinaryProbe} 对存在/不存在命令的判定，以及探测开关关闭时的降级；</li>
 *   <li>工具声明的 requiredBinaries 缺失会被 {@link ToolRegistry#missingBinaries} 命中（从而在 rebuild 中被剔除）；</li>
 *   <li>{@link ShellCommandTool#translateExecError} 把 ENOENT 堆栈转成友好回执，非 ENOENT 原样透传。</li>
 * </ol>
 * 用法：{@code mvn -q compile && java -cp target/classes com.szh.test.ToolAvailabilitySmokeTest}
 * @date 2026/10/14
 */
public class ToolAvailabilitySmokeTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        ConfigUtil.clearCache();
        BinaryProbe.clearCache();
        try {
            testProbeDetectsExistingAndMissing();
            testProbeDisabledDowngrades();
            testMissingBinariesPredicate();
            testTranslateEnoent();
            testTranslatePassthrough();
            testExtractProgramNameEdgeCases();

            System.out.println();
            System.out.println("========================================");
            System.out.println(" ToolAvailabilitySmokeTest: PASS=" + passed + " FAIL=" + failed);
            System.out.println("========================================");
            if (failed > 0) {
                System.exit(1);
            }
        } finally {
            System.clearProperty("tool.probe.binaries.enabled");
            ConfigUtil.clearCache();
            BinaryProbe.clearCache();
        }
    }

    /**
     * 存在命令（sh）判为可用、不存在命令判为不可用。
     */
    private static void testProbeDetectsExistingAndMissing() {
        BinaryProbe.clearCache();
        assertTrue("sh 在 PATH 中应判为可用", BinaryProbe.isAvailable("sh"));
        assertTrue("不存在的命令应判为不可用",
                !BinaryProbe.isAvailable("definitely-not-a-real-cmd-xyz"));
        assertTrue("null 视为无前提可用", BinaryProbe.isAvailable(null));
        assertTrue("空白视为无前提可用", BinaryProbe.isAvailable("   "));
    }

    /**
     * 探测开关关闭时，任何命令（含不存在的）一律返回可用（降级不过滤）。
     */
    private static void testProbeDisabledDowngrades() {
        System.setProperty("tool.probe.binaries.enabled", "false");
        ConfigUtil.clearCache();
        BinaryProbe.clearCache();
        try {
            assertTrue("关闭探测后不存在的命令也判可用",
                    BinaryProbe.isAvailable("definitely-not-a-real-cmd-xyz"));
        } finally {
            System.clearProperty("tool.probe.binaries.enabled");
            ConfigUtil.clearCache();
            BinaryProbe.clearCache();
        }
    }

    /**
     * 过滤谓词：声明缺失命令的假工具会被 missingBinaries 命中（rebuild 会剔除它）；
     * 声明存在命令（sh）及无前提工具返回空列表。
     */
    private static void testMissingBinariesPredicate() {
        List<String> missing = ToolRegistry.missingBinaries(new FakeTool("fake-missing", List.of("definitely-not-a-real-cmd-xyz")));
        assertEquals("缺失命令应被列出", List.of("definitely-not-a-real-cmd-xyz"), missing);

        List<String> present = ToolRegistry.missingBinaries(new FakeTool("fake-sh", List.of("sh")));
        assertTrue("存在命令无缺失", present.isEmpty());

        List<String> none = ToolRegistry.missingBinaries(new FakeTool("no-binary", List.of()));
        assertTrue("无前提工具无缺失", none.isEmpty());
    }

    /**
     * ENOENT 消息被转成友好回执，含「命令未安装」与命令名。
     */
    private static void testTranslateEnoent() {
        String err = "Cannot run program \"mvn\" (in directory \"/app\"): Exec failed, error: 2 (No such file or directory)";
        String out = ShellCommandTool.translateExecError(err);
        assertTrue("友好回执含「命令未安装」", out.contains("命令未安装"));
        assertTrue("友好回执含命令名 mvn", out.contains("mvn"));
        assertTrue("友好回执含「请勿再尝试」", out.contains("请勿再尝试"));
    }

    /**
     * 非 ENOENT 异常原样透传 execute failed 前缀。
     */
    private static void testTranslatePassthrough() {
        String out = ShellCommandTool.translateExecError("permission denied");
        assertEquals("非 ENOENT 原样透传", "execute failed: permission denied", out);
    }

    /**
     * 边界：error=2 变体也应识别；无法解析命令名时用「该命令」占位。
     */
    private static void testExtractProgramNameEdgeCases() {
        String win = "Cannot run program \"git\": error=2, 系统找不到指定的文件";
        String outWin = ShellCommandTool.translateExecError(win);
        assertTrue("error=2 变体也识别为未安装", outWin.contains("命令未安装") && outWin.contains("git"));

        // 命中 ENOENT 关键词但无引号可解析命令名 -> 用「该命令」占位
        String noName = "Cannot run program error: 2 (No such file or directory)";
        String outNoName = ShellCommandTool.translateExecError(noName);
        assertTrue("解析不到命令名时用占位", outNoName.contains("命令未安装") && outNoName.contains("该命令"));
    }

    /**
     * 最小假工具：仅用于验证 requiredBinaries 过滤谓词，不参与执行。
     */
    private static class FakeTool implements Tool {
        private final String code;
        private final List<String> binaries;

        FakeTool(String code, List<String> binaries) {
            this.code = code;
            this.binaries = binaries;
        }

        @Override
        public String getCode() {
            return code;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return null;
        }

        @Override
        public String execute(ToolContext toolContext) {
            throw new UnsupportedOperationException("fake tool 不参与执行");
        }

        @Override
        public List<String> requiredBinaries() {
            return binaries;
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
