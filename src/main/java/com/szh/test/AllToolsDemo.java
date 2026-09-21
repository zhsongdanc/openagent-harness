package com.szh.test;

import com.szh.agent.AgentState;
import com.szh.store.MemoryEventStore;
import com.szh.tool.Tool;
import com.szh.tool.ToolContext;
import com.szh.tool.ToolRegistry;
import com.szh.tool.store.ToolResultStore;
import com.szh.tool.tools.meta.AgentMode;
import com.szh.tool.tools.meta.AgentModeStore;
import com.szh.tool.tools.meta.PlanModePolicy;
import com.szh.tool.tools.meta.TodoStore;
import com.szh.utils.ConfigUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 全工具手工联调入口：把 {@link ToolRegistry} 里注册的工具**逐个直接调用一遍并打印结果**，
 * 不经模型、不烧 token，方便快速验证每个工具的入参解析与输出（含新加的元工具三件套）。
 * <p>
 * 设计取舍：
 * <ul>
 *   <li>用一份精心准备的示例入参跑通每个工具；对有先后依赖的工具（write_file→edit_file→read_file、
 *       先落盘再 read_tool_result、remember→forget）按顺序编排；</li>
 *   <li>写入类演示只落在工作区内 {@code .agent-data/demo/} 下，不污染仓库源码；</li>
 *   <li>{@code git_add/git_commit/git_push/mvn} 默认<b>跳过</b>（会改仓库/远端或耗时长），
 *       置 {@link #RUN_GIT_WRITE}/{@link #RUN_MVN} 为 true 可放开；</li>
 *   <li>{@code ask_user_question} 会读 stdin，正是手工测试点；非交互环境自动走兜底提示；</li>
 *   <li>{@code dispatch_subagent} 需要真实模型：检测到 AK（env DEEPSEEK_API_KEY 或 model.deepseek.apiKey）
 *       才跑，否则跳过并提示；</li>
 *   <li>末尾做一次「兜底扫描」：凡注册表里还没被调用过的工具（如启用的 MCP 工具）用空参兜底跑一遍，
 *       确保「所有工具都调用一遍」。</li>
 * </ul>
 * 运行：{@code java ... com.szh.test.AllToolsDemo}（JDK21）。
 *
 * @author demussong
 * @date 2026/9/22
 */
@Slf4j
public class AllToolsDemo {

    /**
     * 放开 git 写入类工具演示（git_add/git_commit）；默认关闭避免改动仓库
     */
    private static final boolean RUN_GIT_WRITE = false;

    /**
     * 放开 mvn 演示（跑 mvn -v）；默认关闭避免耗时
     */
    private static final boolean RUN_MVN = false;

    /**
     * 单条工具输出打印截断阈值，避免超长输出刷屏
     */
    private static final int PRINT_MAX_CHARS = 1200;

    // 运行期上下文：所有工具共用一个 demo session/run/workspace + 一个内存事件存储的 AgentState
    private static String SESSION;
    private static String RUN = "demo-run";
    private static String WORKSPACE;
    private static AgentState STATE;

    private static int index = 0;
    private static final Set<String> CALLED = new LinkedHashSet<>();

    public static void main(String[] args) {
        // 演示用进程内事件存储 + 关闭 MCP（避免拉起外部子进程）；System property 优先级高于配置文件
        System.setProperty("store.engine", "MEMORY");
        System.setProperty("mcp.enabled", "false");
        System.setProperty("model.stream.enabled", "false");

        WORKSPACE = ConfigUtil.get("project.workspace", System.getProperty("user.dir"));
        SESSION = "demo-" + System.currentTimeMillis();
        STATE = new AgentState(new MemoryEventStore());

        ToolRegistry registry = new ToolRegistry();

        banner();
        System.out.println(" 已注册工具 " + registry.getTools().size() + " 个，workspace=" + WORKSPACE);
        System.out.println(" session=" + SESSION);
        System.out.println("======================================================\n");

        demoQueryTools(registry);
        demoShellTools(registry);
        demoGitTools(registry);
        demoFileTools(registry);
        demoReadToolResult(registry);
        demoMemoryTools(registry);
        demoMetaTools(registry);
        demoSubAgent(registry);
        demoSkipped(registry);
        sweepRemaining(registry);

        System.out.println("\n======================================================");
        System.out.println(" 演示结束：共调用 " + CALLED.size() + " 个工具 -> " + CALLED);
        System.out.println("======================================================");
    }

    // ==================== 分组演示 ====================

    private static void demoQueryTools(ToolRegistry reg) {
        section("查询类（示例工具）");
        run(reg, "queryLocation", "{\"ip\":\"10.13.12.15\"}");
        // queryWeather 内部按 latitude==50.0 判定命中，故用 50.0 触发“晴朗”而非“参数不合理”
        run(reg, "queryWeather", "{\"longitude\":50.0,\"latitude\":50.0}");
    }

    private static void demoShellTools(ToolRegistry reg) {
        section("Shell 只读类");
        run(reg, "pwd", "{}");
        run(reg, "ls", "{\"path\":\".\",\"options\":\"-la\"}");
        run(reg, "find", "{\"path\":\"src/main/java/com/szh/tool/tools/meta\",\"name\":\"*.java\",\"options\":\"-type f\"}");
        run(reg, "head", "{\"path\":\"pom.xml\",\"lines\":10}");
        run(reg, "tail", "{\"path\":\"pom.xml\",\"lines\":5}");
        run(reg, "cat", "{\"path\":\"AGENTS.md\"}");
        run(reg, "grep", "{\"pattern\":\"class\",\"path\":\"src/main/java/com/szh/tool/tools/meta\",\"options\":\"-rn\"}");
    }

    private static void demoGitTools(ToolRegistry reg) {
        section("Git 类");
        run(reg, "git", "{\"command\":\"status -sb\"}");
        run(reg, "git_status", "{}");
        if (RUN_GIT_WRITE) {
            run(reg, "git_add", "{\"paths\":\".agent-data/demo/hello.txt\"}");
            run(reg, "git_commit", "{\"message\":\"chore: AllToolsDemo 演示提交\"}");
        }
        // git_push 永不自动执行（会推远端），仅在 skipped 里说明
    }

    private static void demoFileTools(ToolRegistry reg) {
        section("文件类（写入只落在 .agent-data/demo/）");
        String demoFile = ".agent-data/demo/hello.txt";
        run(reg, "write_file", "{\"path\":\"" + demoFile + "\",\"content\":\"line1\\nline2\\nline3\\n\"}");
        run(reg, "edit_file", "{\"path\":\"" + demoFile + "\",\"old_text\":\"line2\",\"new_text\":\"LINE2-EDITED\"}");
        run(reg, "read_file", "{\"path\":\"" + demoFile + "\",\"offset\":1,\"limit\":10}");
        run(reg, "read_file", "{\"path\":\"pom.xml\",\"offset\":1,\"limit\":12}");
        run(reg, "repo_map", "{\"path\":\"src/main/java/com/szh/tool/tools/meta\",\"mode\":\"symbols\",\"maxDepth\":3}");
    }

    /**
     * read_tool_result 依赖已落盘的结果文件：先用 ToolResultStore 造一份超长结果，再分页回读
     */
    private static void demoReadToolResult(ToolRegistry reg) {
        section("工具结果外存回读");
        StringBuilder big = new StringBuilder();
        for (int i = 1; i <= 120; i++) {
            big.append("line-").append(i).append(": 这是用于演示 read_tool_result 分页回读的填充行\n");
        }
        ToolResultStore store = new ToolResultStore(WORKSPACE, SESSION);
        String resultId = store.store("demo-spilled-result", "grep", big.toString());
        System.out.println("  (预置落盘结果 resultId=" + resultId + "，共 120 行)");
        run(reg, "read_tool_result", "{\"resultId\":\"" + resultId + "\",\"offset\":0,\"limit\":8}");
        CALLED.add("read_tool_result");
    }

    private static void demoMemoryTools(ToolRegistry reg) {
        section("长期记忆类（remember→recall→forget 自我清理）");
        String marker = "OPENAGENT_DEMO_MARKER_" + System.currentTimeMillis();
        run(reg, "remember", "{\"content\":\"" + marker + " 这是一条 AllToolsDemo 演示记忆，用于验证 remember/recall/forget 链路。\","
                + "\"title\":\"演示记忆\",\"category\":\"OTHER\",\"scope\":\"SESSION\",\"keywords\":[\"demo\",\"smoke\"]}");
        run(reg, "recall", "{\"query\":\"" + marker + "\",\"limit\":3}");
        run(reg, "forget", "{\"query\":\"" + marker + "\",\"limit\":1}");
    }

    /**
     * 元工具三件套：todo_write（含 merge 更新 + 渲染）、switch_mode（PLAN 工具收敛对比）、ask_user_question（读 stdin）
     */
    private static void demoMetaTools(ToolRegistry reg) {
        section("元工具（agent 自组织）");

        run(reg, "todo_write", "{\"todos\":["
                + "{\"id\":\"t1\",\"content\":\"分析需求\",\"status\":\"complete\"},"
                + "{\"id\":\"t2\",\"content\":\"实现功能\",\"status\":\"in_progress\"},"
                + "{\"id\":\"t3\",\"content\":\"编写测试\",\"status\":\"pending\"}]}");
        // merge 更新 t2 为完成、追加 t4
        run(reg, "todo_write", "{\"merge\":true,\"todos\":["
                + "{\"id\":\"t2\",\"content\":\"实现功能\",\"status\":\"COMPLETE\"},"
                + "{\"id\":\"t4\",\"content\":\"更新文档\",\"status\":\"PENDING\"}]}");
        System.out.println("  ---- TodoStore.render ----");
        System.out.println(indent(TodoStore.get().render(SESSION)));

        // switch_mode：切 PLAN 看工具收敛，再切回 NORMAL
        run(reg, "switch_mode", "{\"mode\":\"plan\"}");
        List<Tool> all = reg.getTools();
        List<Tool> planTools = PlanModePolicy.effectiveTools(all, SESSION);
        System.out.println("  PLAN 模式工具数 " + planTools.size() + " / 全量 " + all.size()
                + "，被收敛掉的写入类工具：" + diff(all, planTools));
        System.out.println("  当前模式=" + AgentModeStore.get().get(SESSION));
        run(reg, "switch_mode", "{\"mode\":\"normal\"}");
        System.out.println("  切回后模式=" + AgentModeStore.get().get(SESSION)
                + "（PLAN=" + AgentModeStore.get().isPlan(SESSION) + "）");

        // ask_user_question：手工测试点，会读 stdin
        System.out.println("  >>> 下面 ask_user_question 会等待你在控制台输入编号 <<<");
        run(reg, "ask_user_question", "{\"questions\":[{\"question\":\"这个全工具演示跑下来感觉如何？\","
                + "\"header\":\"反馈\",\"multiSelect\":false,\"options\":["
                + "{\"label\":\"都正常\",\"description\":\"所有工具输出符合预期\"},"
                + "{\"label\":\"有报错\",\"description\":\"某些工具报错了，需要排查\"},"
                + "{\"label\":\"还需补充\",\"description\":\"希望再演示别的场景\"}]}]}");
    }

    private static void demoSubAgent(ToolRegistry reg) {
        section("子 agent 派生（需真实模型）");
        if (!hasModelKey()) {
            System.out.println("  [跳过] dispatch_subagent：未检测到模型 AK。");
            System.out.println("         设置环境变量 DEEPSEEK_API_KEY 或配置 model.deepseek.apiKey 后重跑即可演示。");
            return;
        }
        run(reg, "dispatch_subagent", "{\"type\":\"explore\",\"prompt\":\"用一句话说明 com.szh.tool.tools.meta 包是做什么的（只读探索，不改代码）。\"}");
    }

    /**
     * 明确跳过的工具及原因（会改仓库/远端或耗时长），让用户知道它们没被漏掉而是刻意不跑
     */
    private static void demoSkipped(ToolRegistry reg) {
        section("刻意跳过的工具");
        List<String> skipped = new ArrayList<>();
        if (!RUN_GIT_WRITE) {
            skipped.add("git_add / git_commit（会改动仓库，置 RUN_GIT_WRITE=true 放开）");
        }
        skipped.add("git_push（会推远端，永不自动执行）");
        if (!RUN_MVN) {
            skipped.add("mvn（构建耗时长，置 RUN_MVN=true 跑 mvn -v）");
        }
        for (String s : skipped) {
            System.out.println("  - " + s);
        }
        if (RUN_MVN) {
            run(reg, "mvn", "{\"command\":\"-v\"}");
        }
    }

    /**
     * 兜底扫描：注册表里凡是还没被调用过、也不在跳过名单里的工具（如启用的 MCP 工具），
     * 用空参跑一遍，确保「所有工具都调用一遍」
     */
    private static void sweepRemaining(ToolRegistry reg) {
        Set<String> skip = Set.of("git_add", "git_commit", "git_push", "mvn");
        List<Tool> remaining = new ArrayList<>();
        for (Tool t : reg.getTools()) {
            if (!CALLED.contains(t.getCode()) && !skip.contains(t.getCode())) {
                remaining.add(t);
            }
        }
        if (remaining.isEmpty()) {
            return;
        }
        section("兜底扫描（未覆盖到的其余工具，用空参调用）");
        for (Tool t : remaining) {
            run(reg, t.getCode(), "{}");
        }
    }

    // ==================== 执行与打印 ====================

    /**
     * 调用单个工具并打印：编号 + code + 描述 + 入参 + 结果（截断）；工具未注册或抛异常都不中断整体演示
     */
    private static void run(ToolRegistry reg, String code, String argsJson) {
        index++;
        Tool tool = reg.getToolByCode(code);
        System.out.println("------------------------------------------------------");
        System.out.printf("[%02d] %s%n", index, code);
        if (tool == null) {
            System.out.println("  (未注册：可能被配置关闭，如 memory.tools.enabled / meta.tools.enabled / mcp.enabled)");
            return;
        }
        CALLED.add(code);
        String desc = tool.getToolDefinition() == null ? "" : tool.getToolDefinition().getDescription();
        if (desc != null && !desc.isBlank()) {
            System.out.println("  desc: " + firstLine(desc));
        }
        System.out.println("  args: " + argsJson);
        try {
            String out = tool.execute(ctx(argsJson));
            System.out.println("  ---- result ----");
            System.out.println(indent(clip(out)));
        } catch (Exception e) {
            System.out.println("  ---- 执行异常 ----");
            System.out.println("  " + e.getClass().getSimpleName() + ": " + e.getMessage());
            log.warn("tool {} execute failed", code, e);
        }
    }

    private static ToolContext ctx(String argsJson) {
        ToolContext c = new ToolContext(SESSION, RUN, WORKSPACE, argsJson);
        // 注入 AgentState + 轮次信息：元工具据此把 TodoUpdatedEvent/ModeSwitchedEvent 落进（内存）事件日志
        c.setAgentState(STATE);
        c.setTurnId("turn_1");
        c.setRound(1);
        return c;
    }

    // ==================== 工具方法 ====================

    private static boolean hasModelKey() {
        String provider = ConfigUtil.get("model.provider", "deepseek").toLowerCase();
        String envKey = provider.equals("openai") ? "OPENAI_API_KEY" : "DEEPSEEK_API_KEY";
        return present(System.getenv(envKey))
                || present(ConfigUtil.get("model." + provider + ".apiKey"));
    }

    private static boolean present(String s) {
        return s != null && !s.isBlank();
    }

    private static List<String> diff(List<Tool> all, List<Tool> subset) {
        Set<String> subCodes = new LinkedHashSet<>();
        for (Tool t : subset) {
            subCodes.add(t.getCode());
        }
        List<String> removed = new ArrayList<>();
        for (Tool t : all) {
            if (!subCodes.contains(t.getCode())) {
                removed.add(t.getCode());
            }
        }
        return removed;
    }

    private static String clip(String s) {
        if (s == null) {
            return "(null)";
        }
        if (s.length() <= PRINT_MAX_CHARS) {
            return s;
        }
        return s.substring(0, PRINT_MAX_CHARS) + "\n... [输出过长，截断，共 " + s.length() + " 字符]";
    }

    private static String firstLine(String s) {
        int idx = s.indexOf('\n');
        return idx < 0 ? s : s.substring(0, idx);
    }

    /**
     * 给多行文本统一加两格缩进，让结果块在控制台里层次分明
     */
    private static String indent(String s) {
        if (s == null || s.isEmpty()) {
            return "  (空)";
        }
        StringBuilder sb = new StringBuilder();
        for (String line : s.split("\n", -1)) {
            sb.append("  ").append(line).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    private static void section(String title) {
        System.out.println("\n==================== " + title + " ====================");
    }

    private static void banner() {
        System.out.println("======================================================");
        System.out.println(" openagent-harness 全工具手工联调 (AllToolsDemo)");
    }
}
