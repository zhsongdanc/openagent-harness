package com.szh.cli;

import com.szh.agent.AgentResponseRuntime;
import com.szh.agent.AgentRuntime;
import com.szh.agent.AgentState;
import com.szh.agent.verify.VerifyConfig;
import com.szh.agent.verify.VerifyConfigLoader;
import com.szh.agent.verify.VerifyResult;
import com.szh.agent.verify.Verifier;
import com.szh.context.InstructionMemoryLoader;
import com.szh.event.Event;
import com.szh.event.ModeSwitchedEvent;
import com.szh.event.TodoUpdatedEvent;
import com.szh.mcp.client.McpClient;
import com.szh.mcp.client.McpClientManager;
import com.szh.mcp.client.McpToolInfo;
import com.szh.model.ModelFactory;
import com.szh.store.EventStoreFactory;
import com.szh.store.StoreEnum;
import com.szh.trace.TokenTracker;
import com.szh.tool.ToolRegistry;
import com.szh.tool.store.FileSnapshotStore;
import com.szh.tool.tools.meta.AgentMode;
import com.szh.tool.tools.meta.AgentModeStore;
import com.szh.tool.tools.meta.TodoStore;
import com.szh.trace.replay.TraceReplay;
import com.szh.utils.CommonUtils;
import com.szh.utils.ConfigUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * @author demussong
 * @describe 交互式 CLI 会话（REPL）：把原先“一次性 run”的入口升级成可持续对话的人机协作界面。
 * <p>
 * 设计要点：
 * <ol>
 *   <li><b>跨轮复用同一 {@link AgentState} 与运行时实例</b>：模型上下文由 applyEvent 增量维护，
 *       不必每轮从事件流重建，也能在 MEMORY 存储引擎下保持多轮连续（每次 createEventStore 会得到新实例）。</li>
 *   <li><b>双运行时可切换</b>：{@code /runtime chat} 走 Chat Completions（{@link AgentRuntime}），
 *       {@code /runtime response} 走 Responses API（{@link AgentResponseRuntime}），共享同一 session 上下文；
 *       默认由 {@code repl.runtime} 决定。</li>
 *   <li><b>流式去重</b>：开启 {@code model.stream.enabled} 时正文已由 {@code ConsoleStreamListener} 逐 token 打到
 *       stdout，REPL 不再重复打印最终结果，避免同一答案出现两遍。</li>
 *   <li><b>与权限确认共存</b>：危险命令确认由 {@code ConsolePermissionPrompter} 直接读 System.in；
 *       REPL 一次只读一行、run 期间不并发读取，故交互场景下二者不会串台。</li>
 * </ol>
 * 内置命令见 {@link #printHelp()}；也支持 {@code --prompt "..."} 非交互一次性执行便于脚本化。
 * @date 2026/9/21
 */
@Slf4j
public class Repl {

    /**
     * 运行时链路选择
     */
    private enum Mode {
        CHAT, RESPONSE
    }

    /**
     * 项目层指令记忆开关（与 {@code InstructionMemoryLoader} 同一 key，-D 优先于配置文件）
     */
    private static final String PROJECT_MEMORY_KEY = "memory.instruction.project.enabled";

    private final BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));

    private final ToolRegistry toolRegistry = new ToolRegistry();

    /**
     * 流式是否开启：开启则最终答案已边生成边打印，REPL 不再重复输出
     */
    private final boolean streamEnabled = ConfigUtil.getBoolean("model.stream.enabled", true);

    private Mode mode;
    private String sessionId;
    private AgentState agentState;
    private AgentRuntime chatRuntime;
    private AgentResponseRuntime responseRuntime;

    public static void main(String[] args) throws Exception {
        String resumeSession = null;
        Mode mode = parseMode(ConfigUtil.get("repl.runtime", "response"));
        if (mode == null) {
            mode = Mode.RESPONSE;
        }
        String oneShot = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--session" -> resumeSession = next(args, ++i, "--session");
                case "--runtime" -> {
                    Mode parsed = parseMode(next(args, ++i, "--runtime"));
                    if (parsed == null) {
                        throw new IllegalArgumentException("--runtime 仅支持 chat / response");
                    }
                    mode = parsed;
                }
                case "--prompt" -> oneShot = next(args, ++i, "--prompt");
                case "--no-project-memory" ->
                        // 不加载任何仓库的项目层 AGENTS.md：在无关工程/评测任务里跑时，
                        // 项目 internals 对当前任务纯属噪音，还会挤占压缩预算
                        System.setProperty(PROJECT_MEMORY_KEY, "false");
                default -> log.warn("忽略未知参数: {}", args[i]);
            }
        }

        Repl repl = new Repl(mode, resumeSession);
        if (oneShot != null) {
            // 非交互一次性执行：跑完即退出，便于脚本/管道集成
            long startMs = System.currentTimeMillis();
            String res = repl.ask(oneShot);
            if (!repl.streamEnabled) {
                System.out.println(res);
            }
            // 会话到此结束：触发一次 session_end 反思（若策略开启），再退出
            repl.reflectOnSessionEnd();
            repl.emitMetricsSummary(System.currentTimeMillis() - startMs);
            return;
        }
        repl.start();
    }

    public Repl(Mode mode, String resumeSession) {
        this.mode = mode;
        initSession(resumeSession);
    }

    /**
     * 初始化/重置会话：resumeId 命中已持久化 session 则从事件流恢复，否则开新 session
     */
    private void initSession(String resumeId) {
        var store = EventStoreFactory.createEventStore();
        this.agentState = new AgentState(store);
        this.sessionId = null;
        if (resumeId != null && !resumeId.isBlank()) {
            if (store.exists(resumeId)) {
                agentState.resume(resumeId);
                this.sessionId = resumeId;
                System.out.println("已恢复会话: " + resumeId);
            } else {
                System.out.println("未找到会话 " + resumeId + "（存储引擎 " + store.getStoreType() + "），改为新建");
            }
        }
        if (this.sessionId == null) {
            this.sessionId = CommonUtils.generateId();
        }
        // 从事件流回灌元工具状态（待办清单 / 运行模式），使恢复的会话延续 agent 自组织进度
        restoreMetaState();
        // 会话重置后运行时需重建，避免持有旧 AgentState
        this.chatRuntime = null;
        this.responseRuntime = null;
    }

    /**
     * 扫描事件流，把最后一次 TodoUpdatedEvent / ModeSwitchedEvent 回灌进各自的内存 store，
     * 让 {@code /session <id>} 恢复后 REPL 仍能渲染待办清单、运行时仍延续 PLAN/NORMAL 模式。
     * 只读回放，失败不阻断会话初始化。
     */
    private void restoreMetaState() {
        try {
            List<Event> events = agentState.getEventStore().getEvents(sessionId);
            for (Event e : events) {
                if (e instanceof TodoUpdatedEvent t) {
                    TodoStore.get().update(sessionId, t.getTodos());
                } else if (e instanceof ModeSwitchedEvent m) {
                    AgentModeStore.get().set(sessionId, AgentMode.from(m.getMode()));
                }
            }
        } catch (RuntimeException ex) {
            log.warn("restore meta state failed for session {}", sessionId, ex);
        }
    }

    /**
     * 主循环：读一行 -> 命令或对话 -> 打印，直到 /exit 或 EOF(Ctrl-D)
     */
    public void start() throws Exception {
        printBanner();
        while (true) {
            System.out.print("\nyou> ");
            System.out.flush();
            String line = in.readLine();
            if (line == null) {
                // EOF：交互式 Ctrl-D 或非交互输入耗尽，优雅退出
                System.out.println("\n(输入结束，退出)");
                reflectOnSessionEnd();
                break;
            }
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.startsWith("/")) {
                if (handleCommand(trimmed)) {
                    break;
                }
                continue;
            }
            try {
                String res = ask(trimmed);
                show(res);
                renderTodos();
            } catch (Exception e) {
                log.error("run failed", e);
                System.out.println("[出错] " + e.getMessage());
            }
        }
    }

    /**
     * 向当前运行时发一轮对话，返回最终结果
     */
    public String ask(String userInput) {
        return mode == Mode.CHAT
                ? chat().run(sessionId, userInput)
                : response().run(sessionId, userInput);
    }

    /**
     * 会话结束反思：把当前 session 的对话按策略（仅 session_end 生效）蒸馏进 L4 长期记忆。
     * 只在对应运行时已创建（即真的对话过）时调用；session_end 反思在 LongTermMemory 内同步执行，
     * 失败不阻断退出/切换。非 session_end 策略下此调用为 no-op。
     */
    private void reflectOnSessionEnd() {
        try {
            if (mode == Mode.CHAT && chatRuntime != null) {
                chatRuntime.reflectOnSessionEnd(sessionId);
            } else if (mode == Mode.RESPONSE && responseRuntime != null) {
                responseRuntime.reflectOnSessionEnd(sessionId);
            }
        } catch (RuntimeException e) {
            log.warn("session-end reflection failed for {}", sessionId, e);
        }
    }

    /**
     * --prompt 一次性执行结束时打印一行机器可读用量摘要（input/output/total/cached token、轮次、耗时），
     * 供外部评测（Terminal-Bench / Harbor 适配器等）从 stdout 解析回填。仅当 -Dopenagent.metrics.summary=true
     * 时输出，正常交互会话不受影响。
     */
    public void emitMetricsSummary(long elapsedMs) {
        if (!ConfigUtil.getBoolean("openagent.metrics.summary", false)) {
            return;
        }
        TokenTracker t = activeTokenTracker();
        if (t == null) {
            System.out.println("OPENAGENT_METRICS input=0 output=0 total=0 cached=0 rounds=0 elapsed_ms=" + elapsedMs);
            return;
        }
        System.out.println("OPENAGENT_METRICS input=" + t.getCumulativePromptTokens()
                + " output=" + t.getCumulativeCompletionTokens()
                + " total=" + t.getCumulativeTotalTokens()
                + " cached=" + t.getCumulativeCachedTokens()
                + " rounds=" + t.getPerStepUsages().size()
                + " elapsed_ms=" + elapsedMs);
    }

    /** 取当前生效运行时（chat/response）的 token 统计器，未初始化则返回 null。 */
    private TokenTracker activeTokenTracker() {
        return mode == Mode.CHAT
                ? (chatRuntime != null ? chatRuntime.getTokenTracker() : null)
                : (responseRuntime != null ? responseRuntime.getTokenTracker() : null);
    }

    /**
     * 打印本轮结果：流式已逐 token 输出，这里只补分隔；非流式才整体打印
     */
    private void show(String res) {
        if (streamEnabled) {
            return;
        }
        System.out.println("\nagent> " + (res == null ? "" : res));
    }

    /**
     * 每轮结束后自动渲染待办清单（若存在），让用户实时看到 agent 的多步任务进度；无清单则不打印
     */
    private void renderTodos() {
        String rendered = TodoStore.get().render(sessionId);
        if (!rendered.isEmpty()) {
            System.out.println();
            System.out.println(rendered);
        }
    }

    /**
     * /mode [plan|normal]：查看/切换会话运行模式（与 switch_mode 工具写同一份 AgentModeStore）。
     * 用户主动切换同样落 ModeSwitchedEvent 留痕，使 {@code /session} 恢复能回灌模式。
     */
    private void handleMode(String[] parts) {
        AgentModeStore store = AgentModeStore.get();
        if (parts.length < 2) {
            System.out.println("当前模式: " + store.get(sessionId).label() + "（可 /mode plan|normal 切换）");
            return;
        }
        AgentMode target = AgentMode.from(parts[1]);
        store.set(sessionId, target);
        agentState.applyEvent(new ModeSwitchedEvent(sessionId, null, null, 0, target.name()));
        System.out.println("已切换模式: " + target.label());
    }

    private AgentRuntime chat() {
        if (chatRuntime == null) {
            chatRuntime = new AgentRuntime(agentState, toolRegistry, ModelFactory.createChatModel());
        }
        return chatRuntime;
    }

    private AgentResponseRuntime response() {
        if (responseRuntime == null) {
            responseRuntime = new AgentResponseRuntime(agentState, toolRegistry, ModelFactory.createResponseModel());
        }
        return responseRuntime;
    }

    /**
     * 处理内置命令，返回 true 表示需要退出主循环
     */
    private boolean handleCommand(String line) {
        String[] parts = line.split("\\s+");
        String cmd = parts[0].toLowerCase();
        switch (cmd) {
            case "/exit", "/quit" -> {
                reflectOnSessionEnd();
                System.out.println("再见。session=" + sessionId);
                return true;
            }
            case "/help" -> printHelp();
            case "/new" -> {
                // 先给旧会话一次 session_end 反思，再丢弃上下文开新会话
                reflectOnSessionEnd();
                initSession(null);
                System.out.println("已开启新会话: " + sessionId);
            }
            case "/session" -> {
                if (parts.length > 1) {
                    reflectOnSessionEnd();
                    initSession(parts[1]);
                }
                System.out.println("当前 session: " + sessionId + " | 存储引擎: " + EventStoreFactory.getStoreEngine());
            }
            case "/runtime" -> {
                if (parts.length > 1) {
                    Mode target = parseMode(parts[1]);
                    if (target == null) {
                        System.out.println("无效运行时，可选 chat / response");
                    } else {
                        this.mode = target;
                        System.out.println("已切换运行时: " + mode);
                    }
                } else {
                    System.out.println("当前运行时: " + mode + "（可 /runtime chat|response 切换）");
                }
            }
            case "/model" -> printModel();
            case "/memory" -> printMemory();
            case "/todo" -> {
                String rendered = TodoStore.get().render(sessionId);
                System.out.println(rendered.isEmpty() ? "（当前无待办清单）" : rendered);
            }
            case "/mode" -> handleMode(parts);
            case "/clear" -> {
                // 尽力清屏：ANSI 转义在多数终端生效，不支持时退化为空行
                System.out.print("\033[H\033[2J");
                System.out.flush();
            }
            case "/replay" -> replay(parts);
            case "/rewind" -> handleRewind(parts);
            case "/mcp" -> handleMcp(parts);
            case "/verify" -> handleVerify();
            default -> System.out.println("未知命令: " + cmd + "（/help 查看可用命令）");
        }
        return false;
    }

    /**
     * /replay [sessionId] [--html]：回放当前或指定 session 的执行时间线
     */
    private void replay(String[] parts) {
        String target = sessionId;
        boolean html = false;
        for (int i = 1; i < parts.length; i++) {
            if ("--html".equals(parts[i])) {
                html = true;
            } else {
                target = parts[i];
            }
        }
        System.out.println(TraceReplay.render(target));
        if (html) {
            Path file = TraceReplay.exportHtml(target);
            System.out.println(file == null ? "HTML 导出失败，详见日志" : "HTML 已导出: " + file);
        }
    }

    /**
     * /mcp [子命令]：展示 MCP Server 状态 / 重载 / 列出已注册工具
     * <ul>
     *   <li>{@code /mcp}：当前连接与失败概览</li>
     *   <li>{@code /mcp reload}：关闭全部 Server 重新拉起，并重建 ToolRegistry（新 Server 的工具下一轮即生效）</li>
     *   <li>{@code /mcp tools}：按 Server 分组列出全部工具 code + 描述，方便确认名字</li>
     * </ul>
     */
    private void handleMcp(String[] parts) {
        McpClientManager manager = McpClientManager.get();
        manager.ensureInit();
        if (parts.length < 2) {
            System.out.println(manager.statusText());
            System.out.println("子命令：/mcp reload 重载、/mcp tools 列出已注册工具");
            return;
        }
        String sub = parts[1].toLowerCase();
        switch (sub) {
            case "reload" -> {
                manager.reload();
                // 重新扫描工具表，新 Server 的工具不需要重启 REPL 就能用
                toolRegistry.rebuild();
                System.out.println(manager.statusText());
            }
            case "tools" -> {
                int total = 0;
                for (McpClient client : manager.getClients()) {
                    System.out.println("[" + client.getServerName() + "] " + client.getServerInfo());
                    for (McpToolInfo info : client.getTools()) {
                        System.out.println("  - " + client.getServerName() + "__" + info.getName()
                                + (info.getDescription() == null || info.getDescription().isBlank()
                                ? "" : "  // " + firstLine(info.getDescription())));
                        total++;
                    }
                }
                System.out.println("共 " + total + " 个 MCP 工具");
                if (!manager.getFailures().isEmpty()) {
                    System.out.println("启动失败 Server：" + manager.getFailures().keySet());
                }
            }
            default -> System.out.println("未知子命令：/mcp " + sub + "（可选 reload / tools）");
        }
    }

    /**
     * /rewind：回溯到历史时刻（对标 Claude Code 的 /rewind，只覆盖 write_file/edit_file 的文件改动 + 对话上下文）。
     * <ul>
     *   <li>{@code /rewind}：列出可回溯的文件检查点（有改动的轮次 + 变更文件）；</li>
     *   <li>{@code /rewind <N>}：回溯到第 N 轮<b>之前</b>——还原文件、截断事件日志、重建上下文到同一时刻。</li>
     * </ul>
     * 文件还原与上下文回退成对做，保证两者落在同一时刻不脱节；shell/git/子 agent 的副作用不在回溯范围。
     */
    private void handleRewind(String[] parts) {
        if (!FileSnapshotStore.enabled()) {
            System.out.println("回溯未启用（配置 rewind.enabled=true 开启）");
            return;
        }
        String workspace = ConfigUtil.get("project.workspace", System.getProperty("user.dir"));
        if (parts.length < 2) {
            List<FileSnapshotStore.Manifest> checkpoints = FileSnapshotStore.listCheckpoints(workspace, sessionId);
            if (checkpoints.isEmpty()) {
                System.out.println("（当前会话没有可回溯的文件检查点：本会话尚未用 write_file/edit_file 改过文件）");
            } else {
                System.out.println("可回溯的文件检查点（按轮次）：");
                for (FileSnapshotStore.Manifest m : checkpoints) {
                    String time = new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date(m.getTimestamp()));
                    System.out.println("  turn " + m.getTurnSeq() + "  " + time + "  （" + m.getEntries().size() + " 个文件）");
                    for (FileSnapshotStore.Entry e : m.getEntries()) {
                        System.out.println("      " + (e.isExisted() ? "M " : "A ") + e.getRel());
                    }
                }
            }
            System.out.println("用法：/rewind <N> 回溯到第 N 轮之前（撤销第 N 轮及之后的对话与文件改动）");
            return;
        }
        int target;
        try {
            target = Integer.parseInt(parts[1].trim());
        } catch (NumberFormatException e) {
            System.out.println("无效轮次号：" + parts[1] + "（不带参数的 /rewind 可查看可回溯的检查点）");
            return;
        }
        if (target < 1) {
            System.out.println("轮次号必须 >= 1");
            return;
        }
        // 1. 还原文件到第 target 轮之前；2. 截断事件日志（撤销 target 及之后）；3. 重建上下文并回拨 turnId
        FileSnapshotStore.RestoreResult rr = FileSnapshotStore.restoreToTurn(workspace, sessionId, target);
        agentState.getEventStore().truncateFromTurn(sessionId, target);
        agentState.resume(sessionId, target);
        // 4. 元状态（待办/模式）按截断后的事件流重新回灌
        TodoStore.get().clear(sessionId);
        restoreMetaState();
        System.out.println("已回溯到第 " + target + " 轮之前：还原文件 " + rr.getRestored()
                + " 个、删除新建 " + rr.getDeleted() + " 个"
                + (rr.getPaths().isEmpty() ? "" : "（" + String.join(", ", rr.getPaths()) + "）"));
        System.out.println("上下文与 turnId 已同步回退，可继续对话（下一轮为 turn " + target + "）。");
        System.out.println("注意：shell/git/子 agent 的副作用与你的手动改动不在回溯范围内。");
    }

    private static String firstLine(String s) {
        int idx = s.indexOf('\n');
        return idx < 0 ? s : s.substring(0, idx);
    }

    /**
     * /verify：即时对当前工作区跑一遍验证命令并打印结果，不依赖模型。
     * 未启用/未配置命令时给出提示，方便用户确认闸门是否武装。
     */
    private void handleVerify() {
        String workspace = ConfigUtil.get("project.workspace", System.getProperty("user.dir"));
        VerifyConfig cfg = VerifyConfigLoader.load(workspace);
        if (!cfg.isEnabled() || cfg.getCommands().isEmpty()) {
            System.out.println("验证闭环未启用或未配置命令（在 " + workspace
                    + "/.openagent/verify.json 声明 commands 并置 enabled=true）");
            return;
        }
        System.out.println("运行验证命令: " + cfg.getCommands());
        VerifyResult r = Verifier.verify(cfg, workspace);
        System.out.println(r.passed() ? "✓ 验证通过" : "✗ " + r.summary());
    }

    /**
     * 验证闸门武装状态文本：供 banner 与 /model 展示，让用户一眼看到闭环是否生效
     */
    private String verifyStatusText() {
        String workspace = ConfigUtil.get("project.workspace", System.getProperty("user.dir"));
        VerifyConfig cfg = VerifyConfigLoader.load(workspace);
        boolean armed = cfg.isEnabled() && !cfg.getCommands().isEmpty();
        return armed ? "on(" + cfg.getCommands().size() + " 命令)" : "off";
    }

    /**
     * 项目层指令记忆是否武装（-D/配置双通道，与子 agent 默认构造读同一个值）
     */
    private static boolean projectMemoryEnabled() {
        return ConfigUtil.getBoolean(PROJECT_MEMORY_KEY, true);
    }

    /**
     * 记忆状态一行摘要：已装配层数 + 估算 token，让用户一眼看到常驻 system prompt 里项目记忆占多少
     */
    private String memoryStatusText() {
        InstructionMemoryLoader.Assembly assembly = InstructionMemoryLoader.assemble(projectMemoryEnabled());
        return assembly.includedCount() + "层/≈" + assembly.estimatedTokens() + "token"
                + (assembly.skippedCount() > 0 ? "（跳过 " + assembly.skippedCount() + "）" : "");
    }

    /**
     * {@code /memory}：逐层展示分层指令记忆的装配结果——哪层从哪来、多少字符、被哪道闸门跳过及原因，
     * 末尾给总长度与估算 token。用于回答“AGENTS.md 到底生效了没”与“常驻上下文有多贵”这类问题，
     * 把只能靠猜的 prompt 装配变成可观测的。估算仅看量级，权威数字在 {@code TokenUsageEvent} 的 prompt/cached。
     */
    private void printMemory() {
        String workspace = ConfigUtil.get("project.workspace", System.getProperty("user.dir"));
        InstructionMemoryLoader.Assembly assembly = InstructionMemoryLoader.assemble(projectMemoryEnabled());
        System.out.println("分层指令记忆装配（workspace=" + workspace + "）：");
        assembly.layers().forEach(layer -> System.out.println(layer.describe()));
        System.out.println("合计：纳入 " + assembly.includedCount() + " 层 / 跳过 " + assembly.skippedCount()
                + " 层，字符=" + assembly.prompt().length()
                + "，估算 token≈" + assembly.estimatedTokens());
        Path l1Dir = Path.of(workspace).resolve("docs").resolve("agents");
        if (Files.isDirectory(l1Dir)) {
            long count = countL1Docs(l1Dir);
            System.out.println("L1 按需文档：" + l1Dir + " 共 " + count + " 篇（不常驻，命中任务时才 read_file）");
        }
    }

    private static long countL1Docs(Path dir) {
        try (var stream = Files.list(dir)) {
            return stream.filter(p -> p.getFileName().toString().endsWith(".md")).count();
        } catch (Exception e) {
            log.warn("列出 L1 文档失败: {}", dir, e);
            return 0;
        }
    }

    private void printModel() {
        StoreEnum engine = EventStoreFactory.getStoreEngine();
        System.out.println("provider=" + ConfigUtil.get("model.provider", "deepseek")
                + " | model=" + ConfigUtil.get("model.name", "(默认)")
                + " | response.provider=" + ConfigUtil.get("model.response.provider", "(跟随 provider)")
                + " | response.model=" + ConfigUtil.get("model.response.name", "(默认)")
                + " | store=" + engine
                + " | stream=" + streamEnabled
                + " | verify=" + verifyStatusText()
                + " | memory=" + memoryStatusText());
    }

    private void printBanner() {
        System.out.println("======================================================");
        System.out.println(" openagent-harness 交互式会话 (REPL)");
        System.out.println(" session : " + sessionId);
        System.out.println(" runtime : " + mode + "   store: " + EventStoreFactory.getStoreEngine());
        System.out.println(" mode    : " + AgentModeStore.get().get(sessionId));
        System.out.println(" verify  : " + verifyStatusText());
        System.out.println(" memory  : " + memoryStatusText() + "（/memory 看明细）");
        System.out.println(" 输入自然语言对话，/help 查看命令，/exit 退出");
        System.out.println("======================================================");
    }

    private void printHelp() {
        System.out.println("""
                可用命令：
                  /help                 显示本帮助
                  /exit, /quit          退出会话
                  /new                  开启新 session（丢弃当前上下文）
                  /session              显示当前 sessionId 与存储引擎
                  /session <id>         恢复指定已持久化 session
                  /runtime [chat|response]  查看/切换运行时链路（共享同一 session 上下文）
                  /model                查看当前 provider/model/store/stream 配置
                  /memory               查看分层指令记忆装配（哪层生效/被哪道闸门跳过/占多少 token）
                  /todo                 查看当前待办清单（agent 用 todo_write 维护）
                  /mode [plan|normal]   查看/切换运行模式（plan=只读规划，normal=全部工具）
                  /mcp [reload|tools]   查看 MCP Server 状态 / 重载配置 / 列出 MCP 工具
                  /verify               即时跑一遍项目验证命令（编译/测试）并打印结果
                  /replay [id] [--html] 回放执行时间线；--html 额外导出可视化文件
                  /rewind               列出可回溯的文件检查点（write_file/edit_file 改动）
                  /rewind <N>           回溯到第 N 轮之前（同时回退对话上下文与文件）
                  /clear                清屏
                其它任意输入都会作为一轮对话发给模型。""");
    }

    private static Mode parseMode(String value) {
        if (value == null) {
            return Mode.RESPONSE;
        }
        String v = value.trim().toLowerCase();
        if (v.startsWith("chat")) {
            return Mode.CHAT;
        }
        if (v.startsWith("resp")) {
            return Mode.RESPONSE;
        }
        return null;
    }

    private static String next(String[] args, int i, String flag) {
        if (i >= args.length) {
            throw new IllegalArgumentException(flag + " 缺少参数值");
        }
        return args[i];
    }
}
