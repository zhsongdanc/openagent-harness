package com.szh.cli;

import com.szh.agent.AgentResponseRuntime;
import com.szh.agent.AgentRuntime;
import com.szh.agent.AgentState;
import com.szh.mcp.client.McpClient;
import com.szh.mcp.client.McpClientManager;
import com.szh.mcp.client.McpToolInfo;
import com.szh.model.ModelFactory;
import com.szh.store.EventStoreFactory;
import com.szh.store.StoreEnum;
import com.szh.tool.ToolRegistry;
import com.szh.trace.replay.TraceReplay;
import com.szh.utils.CommonUtils;
import com.szh.utils.ConfigUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

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
                default -> log.warn("忽略未知参数: {}", args[i]);
            }
        }

        Repl repl = new Repl(mode, resumeSession);
        if (oneShot != null) {
            // 非交互一次性执行：跑完即退出，便于脚本/管道集成
            String res = repl.ask(oneShot);
            if (!repl.streamEnabled) {
                System.out.println(res);
            }
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
        // 会话重置后运行时需重建，避免持有旧 AgentState
        this.chatRuntime = null;
        this.responseRuntime = null;
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
     * 打印本轮结果：流式已逐 token 输出，这里只补分隔；非流式才整体打印
     */
    private void show(String res) {
        if (streamEnabled) {
            return;
        }
        System.out.println("\nagent> " + (res == null ? "" : res));
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
                System.out.println("再见。session=" + sessionId);
                return true;
            }
            case "/help" -> printHelp();
            case "/new" -> {
                initSession(null);
                System.out.println("已开启新会话: " + sessionId);
            }
            case "/session" -> {
                if (parts.length > 1) {
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
            case "/clear" -> {
                // 尽力清屏：ANSI 转义在多数终端生效，不支持时退化为空行
                System.out.print("\033[H\033[2J");
                System.out.flush();
            }
            case "/replay" -> replay(parts);
            case "/mcp" -> handleMcp(parts);
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

    private static String firstLine(String s) {
        int idx = s.indexOf('\n');
        return idx < 0 ? s : s.substring(0, idx);
    }

    private void printModel() {
        StoreEnum engine = EventStoreFactory.getStoreEngine();
        System.out.println("provider=" + ConfigUtil.get("model.provider", "deepseek")
                + " | model=" + ConfigUtil.get("model.name", "(默认)")
                + " | response.provider=" + ConfigUtil.get("model.response.provider", "(跟随 provider)")
                + " | response.model=" + ConfigUtil.get("model.response.name", "(默认)")
                + " | store=" + engine
                + " | stream=" + streamEnabled);
    }

    private void printBanner() {
        System.out.println("======================================================");
        System.out.println(" openagent-harness 交互式会话 (REPL)");
        System.out.println(" session : " + sessionId);
        System.out.println(" runtime : " + mode + "   store: " + EventStoreFactory.getStoreEngine());
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
                  /mcp [reload|tools]   查看 MCP Server 状态 / 重载配置 / 列出 MCP 工具
                  /replay [id] [--html] 回放执行时间线；--html 额外导出可视化文件
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
