package com.szh.test;

import com.szh.context.dto.AssistantMessageItem;
import com.szh.context.dto.MessageItem;
import com.szh.context.dto.ReasoningMessageItem;
import com.szh.context.dto.SystemMessageItem;
import com.szh.context.dto.ToolMessageItem;
import com.szh.context.dto.UserMessageItem;
import com.szh.event.CallToolFinishedEvent;
import com.szh.event.CallToolStartedEvent;
import com.szh.event.ContextCompactedEvent;
import com.szh.event.Event;
import com.szh.event.ModeSwitchedEvent;
import com.szh.event.ModelResponseEvent;
import com.szh.event.ReasoningEvent;
import com.szh.event.RunCompletedEvent;
import com.szh.event.RunStartedEvent;
import com.szh.event.TokenUsageEvent;
import com.szh.event.TodoUpdatedEvent;
import com.szh.event.UserMessageEvent;
import com.szh.event.VerificationEvent;
import com.szh.model.dto.TokenUsage;
import com.szh.model.dto.ToolCall;
import com.szh.store.EventJsonCodec;
import com.szh.store.FileEventStore;
import com.szh.tool.Tool;
import com.szh.tool.ToolContext;
import com.szh.tool.ToolDefinition;
import com.szh.tool.tools.meta.TodoItem;
import com.szh.tool.tools.meta.TodoStatus;
import com.szh.trace.context.ContextSnapshot;
import com.szh.trace.context.ContextSnapshotStore;
import com.szh.web.ContextReplayer;
import com.szh.web.ContextSnapshotReader;
import com.szh.web.SessionCatalog;
import com.szh.web.SessionViewBuilder;
import com.szh.web.TraceWebServer;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 本地 Trace 查看器（Web Viewer）冒烟验证（无测试框架，按项目惯例 main 直跑）：
 * 全程写入临时目录（{@code store.file.dir} / {@code trace.context.dir} 都被指到 tmp），
 * 不触达真实模型 API、不碰 MySQL、不读 stdin。
 * <ol>
 *   <li>会话清单：扫目录得到会话、按最近活动倒序、子 agent 会话打标并回指父会话、token 汇总；</li>
 *   <li>结构化视图：turn → run → round 分组、每轮 token/上下文/步骤、并行工具调用配对、最终答复；</li>
 *   <li>TOKEN_USAGE 事件：编解码往返不失真，usage=null 不 NPE（FakeModel 场景）；</li>
 *   <li>上下文快照：写侧落盘 → 读侧按轮取回，tools/messages 完整，超限与关闭开关时静默跳过；</li>
 *   <li>上下文重建：无快照时按事件流折叠 {@code [system, 摘要, 尾部 keepRecentCount]}，与 AgentState 同口径；</li>
 *   <li>HTTP：各接口 200 + 关键字段，静态资源可取，未知路径 404；</li>
 *   <li>安全：tool-result 路径穿越被 sanitize 拒绝，不泄漏工作区外文件。</li>
 * </ol>
 *
 * @author demussong
 * @date 2026/10/15
 */
@Slf4j
public class WebViewerSmokeTest {

    private static final AtomicInteger PASSED = new AtomicInteger();
    private static final AtomicInteger FAILED = new AtomicInteger();

    private static final String MAIN_SESSION = "smoke-main";
    private static final String SUB_SESSION = "smoke-main-sub-abcdef12";
    private static final String RUN_1 = "run-smoke-1";

    private static Path root;
    private static Path workspace;

    public static void main(String[] args) throws Exception {
        root = Files.createTempDirectory("openagent-web-smoke");
        workspace = root.resolve("workspace");
        Files.createDirectories(workspace);

        // 全部落盘位置指到临时目录，冒烟不污染真实的 ~/.openagent，也不依赖既有会话
        System.setProperty("project.workspace", workspace.toString());
        System.setProperty("store.engine", "FILE");
        System.setProperty("store.file.dir", root.resolve("sessions").toString());
        System.setProperty("trace.context.enabled", "true");
        System.setProperty("trace.context.dir", root.resolve("context").toString());
        System.setProperty("memory.enabled", "false");
        System.setProperty("memory.tools.enabled", "false");
        System.setProperty("shell.security.enabled", "false");
        System.setProperty("mcp.enabled", "false");
        System.setProperty("model.stream.enabled", "false");

        seedEvents();
        seedSnapshot();
        seedToolResult();

        testCatalogList();
        testViewStructure();
        testTokenUsageEvent();
        testSnapshotRead();
        testContextRebuildWithCompaction();
        testHttpApis();
        testPathTraversal();

        log.info("====== Trace 查看器冒烟结果: passed={}, failed={} ======", PASSED.get(), FAILED.get());
        if (FAILED.get() > 0) {
            System.exit(1);
        }
    }

    // ==================== 数据准备 ====================

    /**
     * 造一份足够真实的事件流：一轮多工具并行 + 一轮最终答复，外加 TODO/MODE/VERIFY/COMPACT 四类 surface 事件
     */
    private static void seedEvents() {
        FileEventStore store = new FileEventStore();
        long base = 1_800_000_000_000L;
        List<Event> events = new ArrayList<>();

        events.add(new RunStartedEvent(MAIN_SESSION, RUN_1, "turn_1", 0));
        events.add(new UserMessageEvent(MAIN_SESSION, RUN_1, "turn_1", 0,
                new UserMessageItem("帮我把这个项目编译一下"), "帮我把这个项目编译一下"));

        // round 1：思考 + 并行两次工具调用 + 结果 + token + 元工具标记
        events.add(new ReasoningEvent(MAIN_SESSION, RUN_1, "turn_1", 1,
                new ReasoningMessageItem("先看目录再决定编译命令")));
        events.add(new ModelResponseEvent(MAIN_SESSION, RUN_1, "turn_1", 1,
                new AssistantMessageItem(List.of(new ToolCall("call-1", "read_file", "{\"path\":\"pom.xml\"}"),
                        new ToolCall("call-2", "mvn", "{\"command\":\"mvn -q compile\"}")))));
        events.add(new CallToolStartedEvent(MAIN_SESSION, RUN_1, "turn_1", 1, "read_file", "{\"path\":\"pom.xml\"}"));
        events.add(new CallToolStartedEvent(MAIN_SESSION, RUN_1, "turn_1", 1, "mvn", "{\"command\":\"mvn -q compile\"}"));
        events.add(new CallToolFinishedEvent(MAIN_SESSION, RUN_1, "turn_1", 1,
                new ToolMessageItem("call-1", "read_file", "[结果已落盘] result_id=call-1，预览：JDK21"),
                "read_file", "[结果已落盘] result_id=call-1"));
        events.add(new CallToolFinishedEvent(MAIN_SESSION, RUN_1, "turn_1", 1,
                new ToolMessageItem("call-2", "mvn", "BUILD SUCCESS"), "mvn", "BUILD SUCCESS"));
        events.add(new TokenUsageEvent(MAIN_SESSION, RUN_1, "turn_1", 1,
                new TokenUsage(100, 30, 130, 80), 5, 2048, 450L));
        events.add(new TodoUpdatedEvent(MAIN_SESSION, RUN_1, "turn_1", 1, List.of(
                todo("t1", "编译验证", TodoStatus.IN_PROGRESS))));
        events.add(new ModeSwitchedEvent(MAIN_SESSION, RUN_1, "turn_1", 1, "plan"));

        // round 2：最终答复；token 事件刻意用 usage=null（FakeModel/无 usage 的 provider）验证不 NPE
        events.add(new ModelResponseEvent(MAIN_SESSION, RUN_1, "turn_1", 2,
                new AssistantMessageItem("已完成编译，无错误")));
        events.add(new VerificationEvent(MAIN_SESSION, RUN_1, "turn_1", 2, true, "mvn -q compile", 0, "BUILD SUCCESS"));
        events.add(new ContextCompactedEvent(MAIN_SESSION, RUN_1, "turn_1", 2, "本轮摘要：编译通过", 2, 9, 4));
        events.add(new TokenUsageEvent(MAIN_SESSION, RUN_1, "turn_1", 2, (TokenUsage) null, 7, 3000, 210L));
        events.add(new RunCompletedEvent(MAIN_SESSION, RUN_1, "turn_1", 2, "已完成编译，无错误"));

        long ts = base;
        for (Event event : events) {
            event.setTimestamp(ts += 1000);
            store.appendEvent(event);
        }

        // 子 agent 会话：只一条用户输入 + 一条最终答复，用于验证列表打标
        List<Event> sub = List.of(
                new UserMessageEvent(SUB_SESSION, "run-sub", "turn_1", 0,
                        new UserMessageItem("检索一下 README"), "检索一下 README"),
                new RunCompletedEvent(SUB_SESSION, "run-sub", "turn_1", 1, "README 摘要"));
        long subTs = base + 100_000;
        for (Event event : sub) {
            event.setTimestamp(subTs += 1000);
            store.appendEvent(event);
        }
    }

    /**
     * 写侧快照：round 1 一条正常快照（含工具 code 列表），另造超限与关闭开关两种兜底场景
     */
    private static void seedSnapshot() throws IOException {
        ContextSnapshotStore store = new ContextSnapshotStore();
        List<MessageItem> callContext = new ArrayList<>();
        callContext.add(new SystemMessageItem("你是编码助手（含运行期装饰后的 system prompt）"));
        callContext.add(new UserMessageItem("帮我把这个项目编译一下"));
        callContext.add(new AssistantMessageItem("call-1", "read_file", "{\"path\":\"pom.xml\"}"));
        callContext.add(new ToolMessageItem("call-1", "read_file", "[结果已落盘] result_id=call-1"));
        store.record(MAIN_SESSION, RUN_1, "turn_1", 1, callContext, List.of(new FakeTool("mvn"), new FakeTool("read_file")));

        // 超限兜底：把上限压到 1 字节并重建 store（配置在构造时读取），record 应只告警、不落这一行
        System.setProperty("trace.context.max.bytes", "1");
        new ContextSnapshotStore().record(MAIN_SESSION, RUN_1, "turn_1", 99, callContext, null);
        check("快照超单条上限被跳过（该 run 文件仍只有 round 1 一行）",
                Files.readAllLines(store.file(MAIN_SESSION, RUN_1)).size() == 1);
        check("快照文件按 run 落盘", Files.exists(store.file(MAIN_SESSION, RUN_1)));
        System.clearProperty("trace.context.max.bytes");

        // 开关兜底：关闭后不写任何快照
        System.setProperty("trace.context.enabled", "false");
        new ContextSnapshotStore().record("smoke-off", "run-off", "turn_1", 1, callContext, List.of());
        System.setProperty("trace.context.enabled", "true");
        check("关闭开关后不写快照", !Files.exists(root.resolve("context").resolve("smoke-off")));
    }

    /**
     * 落盘的工具结果全文：查看器按需拉取，验证正文可见与路径安全
     */
    private static void seedToolResult() {
        try {
            Path dir = workspace.resolve(".agent-data").resolve("tool-results").resolve(MAIN_SESSION);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("call-1.txt"), "第一行\n第二行含关键字 JDK21\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            fail("准备工具结果全文文件", e);
        }
    }

    // ==================== ① 会话清单 ====================

    private static void testCatalogList() {
        SessionCatalog catalog = new SessionCatalog();
        List<Map<String, Object>> sessions = catalog.list();
        check("扫目录得到两个会话", sessions.size() == 2);
        check("存储引擎为 FILE", catalog.storeType().name().equals("FILE"));
        check("会话目录可读", catalog.sessionDir() != null && Files.isDirectory(catalog.sessionDir()));

        Map<String, Object> main = find(sessions, MAIN_SESSION);
        check("主会话未被误标为子会话", main != null && Boolean.FALSE.equals(main.get("sub")));
        check("主会话事件数正确", main != null && num(main.get("events")) == 16);
        check("主会话 token 汇总自 TOKEN_USAGE 事件", main != null && num(main.get("totalTokens")) == 130);
        check("主会话 prompt/cached 分别汇总", main != null && num(main.get("promptTokens")) == 100
                && num(main.get("cachedTokens")) == 80);
        check("主会话带最近用户输入", main != null && String.valueOf(main.get("lastUserInput")).contains("编译"));
        // 前端靠这个计数区分「记录为 0」与「根本没记录」，不能拿数值本身判真假
        check("主会话记录了 2 轮的 token", main != null && num(main.get("tokenRounds")) == 2);
        check("usage=null 的那轮也算已记录（计入 tokenRounds）",
                main != null && num(main.get("tokenRounds")) == 2 && num(main.get("totalTokens")) == 130);

        Map<String, Object> sub = find(sessions, SUB_SESSION);
        check("子 agent 会话被打标", sub != null && Boolean.TRUE.equals(sub.get("sub")));
        check("子 agent 会话回指父会话", sub != null && MAIN_SESSION.equals(sub.get("parent")));
        check("列表按最近活动倒序（子会话在后写入，时间更大）",
                sessions.get(0).get("sessionId").equals(SUB_SESSION));
        check("子会话无 token 事件时汇总为 0", sub != null && num(sub.get("totalTokens")) == 0);
        check("子会话 tokenRounds=0（UI 应显「未记录」而非数字 0）", sub != null && num(sub.get("tokenRounds")) == 0);
    }

    // ==================== ② 结构化视图 ====================

    private static void testViewStructure() {
        SessionCatalog catalog = new SessionCatalog();
        SessionViewBuilder builder = new SessionViewBuilder();
        Map<String, Object> view = builder.build(MAIN_SESSION, catalog.events(MAIN_SESSION),
                new ContextSnapshotReader());

        List<Map<String, Object>> turns = castList(view.get("turns"));
        check("一次用户输入归为一个 turn", turns.size() == 1);
        Map<String, Object> turn1 = turns.get(0);
        check("turn 带用户输入首条", String.valueOf(turn1.get("userInput")).contains("编译"));

        List<Map<String, Object>> runs = castList(turn1.get("runs"));
        check("每个 turn 下挂一个 run", runs.size() == 1);
        Map<String, Object> run = runs.get(0);
        check("run 汇总 token = 130", num(run.get("totalTokens")) == 130);
        check("run 时长由首尾事件算出", num(run.get("durationMs")) > 0);
        check("run 带最终答复", String.valueOf(run.get("finalAnswer")).contains("已完成编译"));

        List<Map<String, Object>> rounds = castList(run.get("rounds"));
        check("run 内按 round 序号切分为 3 段（round0 入场 + 两轮模型调用）", rounds.size() == 3);

        // round 0：只有 run 开始与用户输入，没有任何 token 事件 → 面板应显示「未记录」而非 0
        Map<String, Object> r0 = rounds.get(0);
        check("无 TOKEN_USAGE 的轮次 tokens 为 null", r0.get("tokens") == null && r0.get("modelMs") == null);
        check("无 TOKEN_USAGE 的轮次上下文规模也为 null", r0.get("contextSize") == null);
        check("入场轮不命中快照", Boolean.FALSE.equals(r0.get("hasSnapshot")));

        // round 1：完整的 token / 上下文 / 工具配对
        Map<String, Object> r1 = rounds.get(1);
        Map<String, Object> tokens = castMap(r1.get("tokens"));
        check("每轮 token 明细（prompt/completion/total/cached）", tokens != null
                && num(tokens.get("promptTokens")) == 100 && num(tokens.get("cachedTokens")) == 80);
        Map<String, Object> ctx = castMap(r1.get("contextSize"));
        check("每轮上下文规模（条数 + 估算 token）", ctx != null
                && num(ctx.get("messages")) == 5 && num(ctx.get("estimatedTokens")) == 2048);
        check("每轮模型耗时来自 elapsedMs", num(r1.get("modelMs")) == 450);
        check("每轮标注是否有精确快照", Boolean.TRUE.equals(r1.get("hasSnapshot")));

        // round 2：usage=null 落库后应为 0（与「未记录」区分开），且不抛异常
        Map<String, Object> r2 = rounds.get(2);
        Map<String, Object> zeroTokens = castMap(r2.get("tokens"));
        check("usage=null 的轮次 token 记为 0 而非缺失", zeroTokens != null
                && num(zeroTokens.get("totalTokens")) == 0);
        check("round 2 无快照（只有 round 1 落了）", Boolean.FALSE.equals(r2.get("hasSnapshot")));

        List<Map<String, Object>> tools = castList(r1.get("tools"));
        check("并行两次工具调用按调用原序配对", tools.size() == 2
                && "call-1".equals(tools.get(0).get("callId")) && "call-2".equals(tools.get(1).get("callId")));
        check("工具结果按 callId 回填 output", "BUILD SUCCESS".equals(tools.get(1).get("output")));

        List<Map<String, Object>> steps = castList(r1.get("steps"));
        check("步骤序列含 reasoning/model/tokens/todo/mode 各类型", kinds(steps).contains("reasoning")
                && kinds(steps).contains("model") && kinds(steps).contains("tokens")
                && kinds(steps).contains("todo") && kinds(steps).contains("mode"));
        check("model 步骤带结构化 toolCalls", firstStep(steps, "model").get("toolCalls") instanceof List<?> list
                && ((List<?>) list).size() == 2);
        List<Map<String, Object>> doneSteps = castList(r2.get("steps"));
        check("收尾轮含 VERIFY 与 COMPACT 标记", kinds(doneSteps).contains("verify")
                && kinds(doneSteps).contains("compact"));

        Map<String, Object> summary = castMap(view.get("summary"));
        check("会话级汇总含事件数/run 数/turn 数", summary != null && num(summary.get("events")) == 16
                && num(summary.get("runs")) == 1 && num(summary.get("turns")) == 1);
        check("会话/turn/run 三级都带 tokenRounds 计数供前端区分未记录与 0",
                num(summary.get("tokenRounds")) == 2 && num(turn1.get("tokenRounds")) == 2
                        && num(run.get("tokenRounds")) == 2);
    }

    // ==================== ③ TOKEN_USAGE 事件编解码 ====================

    private static void testTokenUsageEvent() {
        TokenUsageEvent origin = new TokenUsageEvent("s", "r", "t", 3,
                new TokenUsage(11, 22, 33, 44), 6, 777, 88L);
        Event back = EventJsonCodec.deserialize(EventJsonCodec.serialize(origin));
        check("TOKEN_USAGE 经事件编解码往返", back instanceof TokenUsageEvent t
                && t.getPromptTokens() == 11 && t.getCompletionTokens() == 22 && t.getTotalTokens() == 33
                && t.getCachedTokens() == 44 && t.getContextMessages() == 6
                && t.getContextEstimatedTokens() == 777 && t.getElapsedMs() == 88L);
        Event surface = origin;
        check("TOKEN_USAGE 是 surface 事件（不继承 MessageEvent，不进 modelContext）",
                !(surface instanceof com.szh.event.MessageEvent));

        // usage=null（FakeModel / provider 未回 usage）必须安全兜底为 0，不得 NPE
        TokenUsageEvent nullUsage = new TokenUsageEvent("s", "r", "t", 1, (TokenUsage) null, 2, 100, 5L);
        check("usage=null 兜底为 0 且不抛", nullUsage.getTotalTokens() == 0 && nullUsage.getPromptTokens() == 0);
        Event nullBack = EventJsonCodec.deserialize(EventJsonCodec.serialize(nullUsage));
        check("usage=null 往返仍是 TOKEN_USAGE", nullBack instanceof TokenUsageEvent);

        // 老 JSONL（无该类型）反序列化不得炸：未知类型应被忽略而非抛异常
        check("损坏/未知 payload 不抛异常", decodeSafely("{\"eventType\":\"USER_INPUT\",\"payload\":\"not-a-json\"}"));
    }

    private static boolean decodeSafely(String rawLine) {
        try {
            EventJsonCodec.deserialize(com.szh.utils.JsonUtil.parse(rawLine,
                    com.szh.store.SerializedEvent.class));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ==================== ④ 快照读侧 ====================

    private static void testSnapshotRead() {
        ContextSnapshotReader reader = new ContextSnapshotReader();
        check("读侧与写侧同源目录", reader.rootDir().equals(root.resolve("context")));
        check("快照开关开启", reader.enabled());
        check("按 run 列出已落快照的轮次", reader.rounds(MAIN_SESSION, RUN_1).contains(1));
        check("未知 run 无快照", reader.rounds(MAIN_SESSION, "no-such-run").isEmpty());

        ContextSnapshot snapshot = reader.read(MAIN_SESSION, RUN_1, 1);
        check("读取指定轮快照", snapshot != null && snapshot.round() == 1);
        check("快照消息条数与正文一致", snapshot != null && snapshot.messageCount() == 4
                && snapshot.messages().size() == 4);
        check("快照逐条带 kind 与估算 token", snapshot != null
                && "SYSTEM".equals(snapshot.messages().get(0).kind())
                && snapshot.messages().get(0).estimatedTokens() > 0);
        check("快照保留 assistant 的结构化 toolCalls", snapshot != null
                && snapshot.messages().get(2).toolCalls() != null
                && "read_file".equals(snapshot.messages().get(2).toolCalls().get(0).code()));
        check("快照带本轮可见工具列表", snapshot != null && snapshot.tools().contains("mvn"));
        check("缺失轮返回 null（前端回退重建）", reader.read(MAIN_SESSION, RUN_1, 9) == null);
        check("runId=null 不抛", reader.read(MAIN_SESSION, null, 1) == null);
    }

    // ==================== ⑤ 上下文重建（含压缩折叠） ====================

    private static void testContextRebuildWithCompaction() {
        ContextReplayer replayer = new ContextReplayer();

        // 事件流：4 条用户消息 → 压缩检查点（保留尾部 2 条）→ 目标轮第一条消息
        String runId = "run-rebuild";
        List<Event> events = new ArrayList<>();
        long ts = 1_700_000_000_000L;
        for (int i = 1; i <= 4; i++) {
            events.add(user(runId, i, "m" + i, ts += 1000));
        }
        events.add(new ContextCompactedEvent("s", runId, "turn_1", 5, "这是交接摘要", 2, 4, 3));
        events.add(user(runId, 5, "m5", ts += 1000));
        events.add(user(runId, 5, "m6", ts += 1000));

        ContextReplayer.Rebuilt rebuilt = replayer.rebuild(events, runId, 5);
        check("无快照时可由事件流重建", rebuilt != null);
        List<String> contents = rebuilt == null ? List.of()
                : rebuilt.messages().stream().map(m -> String.valueOf(m.content())).toList();
        check("折叠为 [system, 摘要, 尾部 keepRecentCount 条]", rebuilt != null
                && rebuilt.messages().size() == 4, "实际=" + contents);
        check("重建首条是 system prompt", rebuilt != null
                && "SYSTEM".equals(rebuilt.messages().get(0).kind()));
        check("第二条是压缩摘要正文", rebuilt != null && contents.size() > 1 && "这是交接摘要".equals(contents.get(1)));
        check("尾部保留 m3/m4，早期 m1/m2 已被折叠", rebuilt != null
                && contents.contains("m3") && contents.contains("m4") && !contents.contains("m1"));
        check("目标轮之后的消息不进入上下文", rebuilt != null && !contents.contains("m5"));
        check("重建带估算 token 合计", rebuilt != null && rebuilt.estimatedTokens() > 0);
        check("事件流里没有该 run 时返回 null", replayer.rebuild(events, "no-run", 1) == null);
        check("runId=null 不抛", replayer.rebuild(events, null, 1) == null);

        // 未压缩的会话：重建即完整累积历史（只有 MessageEvent 进上下文）
        String plainRun = "run-plain";
        List<Event> plain = new ArrayList<>();
        plain.add(new RunStartedEvent("s", plainRun, "turn_1", 0));
        plain.add(user(plainRun, 1, "hello", ts += 1000));
        plain.add(new TodoUpdatedEvent("s", plainRun, "turn_1", 1, List.of(todo("t", "x", TodoStatus.PENDING))));
        plain.add(user(plainRun, 2, "world", ts += 1000));
        ContextReplayer.Rebuilt plainRebuilt = replayer.rebuild(plain, plainRun, 2);
        check("surface 事件不进上下文（仅 system + hello）", plainRebuilt != null
                && plainRebuilt.messages().size() == 2
                && "hello".equals(plainRebuilt.messages().get(1).content()));
    }

    private static UserMessageEvent user(String runId, int round, String text, long ts) {
        UserMessageEvent event = new UserMessageEvent("s", runId, "turn_1", round,
                new UserMessageItem(text), text);
        event.setTimestamp(ts);
        return event;
    }

    // ==================== ⑥ HTTP 接口 ====================

    private static void testHttpApis() throws Exception {
        TraceWebServer server = TraceWebServer.start("127.0.0.1", 0, workspace.toString());
        String base = "http://127.0.0.1:" + server.port();
        try {
            Resp index = get(base + "/");
            check("GET / 返回单页 HTML", index.status == 200 && index.body.contains("OpenAgent"));
            check("GET /static/app.js 可取", get(base + "/static/app.js").status == 200);
            check("GET /static/style.css 可取", get(base + "/static/style.css").status == 200);

            Resp meta = get(base + "/api/meta");
            check("GET /api/meta 200 且含引擎/工作区/快照目录", meta.status == 200
                    && meta.body.contains("\"storeEngine\":\"FILE\"")
                    && meta.body.contains("\"snapshotEnabled\":true")
                    && meta.body.contains("workspace"), meta.body);

            Resp sessions = get(base + "/api/sessions");
            check("GET /api/sessions 列出会话", sessions.status == 200
                    && sessions.body.contains(MAIN_SESSION) && sessions.body.contains(SUB_SESSION));

            Resp detail = get(base + "/" + "api/sessions/" + MAIN_SESSION);
            check("GET /api/sessions/{id} 带 turn/run/round 结构", detail.status == 200
                    && detail.body.contains("\"turns\"") && detail.body.contains("\"rounds\"")
                    && detail.body.contains("\"hasSnapshot\":true"), head(detail.body));
            check("会话详情默认不带上下文正文（避免整包过大）", !detail.body.contains("送给模型的上下文"));

            Resp ctx = get(base + "/api/sessions/" + MAIN_SESSION + "/context?runId=" + RUN_1 + "&round=1");
            check("GET /context 命中精确快照", ctx.status == 200 && ctx.body.contains("\"source\":\"snapshot\"")
                    && ctx.body.contains("你是编码助手"), head(ctx.body));

            Resp ctxMissing = get(base + "/api/sessions/" + MAIN_SESSION + "/context?runId=" + RUN_1 + "&round=2");
            check("快照缺失时回退事件流重建", ctxMissing.status == 200
                    && ctxMissing.body.contains("\"source\":\"rebuilt\"")
                    && ctxMissing.body.contains("帮我把这个项目编译一下"), head(ctxMissing.body));

            Resp events = get(base + "/api/sessions/" + MAIN_SESSION + "/events?offset=0&limit=20");
            check("GET /events 分页返回原始事件（含 payload 原文）", events.status == 200
                    && events.body.contains("\"total\":16") && events.body.contains("TOKEN_USAGE")
                    && events.body.contains("toolName") && events.body.contains("\"payload\":\""), head(events.body));
            Resp page = get(base + "/api/sessions/" + MAIN_SESSION + "/events?offset=2&limit=1");
            check("/events 分页参数生效（只返 1 条）", page.status == 200
                    && count(page.body, "\"id\":\"") == 1, head(page.body));

            Resp result = get(base + "/api/tool-result?session=" + MAIN_SESSION + "&callId=call-1");
            check("GET /tool-result 取回落盘全文", result.status == 200
                    && result.body.contains("JDK21") && result.body.contains("\"totalLines\":2"), head(result.body));

            Resp unknown = get(base + "/api/nope");
            check("未知路径 404 + JSON 错误", unknown.status == 404 && unknown.body.contains("error"));
            Resp missing = get(base + "/api/sessions/no-such-session");
            check("不存在会话 404", missing.status == 404);
            Resp badSession = get(base + "/api/sessions/" + java.net.URLEncoder
                    .encode("../../etc/passwd", StandardCharsets.UTF_8));
            check("会话 id 非法时不落到外部文件", badSession.status == 404 || badSession.status == 500);
        } finally {
            server.stop();
        }
    }

    // ==================== ⑦ 路径穿越防护 ====================

    private static void testPathTraversal() throws Exception {
        TraceWebServer server = TraceWebServer.start("127.0.0.1", 0, workspace.toString());
        String base = "http://127.0.0.1:" + server.port();
        try {
            String traversal = java.net.URLEncoder.encode("../../../etc/passwd", StandardCharsets.UTF_8);
            Resp resp = get(base + "/api/tool-result?session=" + traversal + "&callId=" + traversal);
            check("tool-result 路径穿越被 sanitize 拒绝（404 而非泄漏正文）",
                    resp.status == 404 && !resp.body.contains("root:"), head(resp.body));

            Resp staticTraversal = get(base + "/static/..%2F..%2F..%2Fpom.xml");
            check("静态资源目录穿越被拒", staticTraversal.status != 200, head(staticTraversal.body));

            Resp noParam = get(base + "/api/tool-result");
            check("缺参数返回 400", noParam.status == 400);
        } finally {
            server.stop();
        }
    }

    // ==================== 辅助 ====================

    private record Resp(int status, String body) {
    }

    private static Resp get(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(10000);
        int status = conn.getResponseCode();
        InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String body = in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        conn.disconnect();
        return new Resp(status, body);
    }

    private static TodoItem todo(String id, String content, TodoStatus status) {
        TodoItem item = new TodoItem();
        item.setId(id);
        item.setContent(content);
        item.setStatus(status);
        return item;
    }

    /**
     * 只用于验证快照记录工具 code 列表的最小 Tool 实现（查看器只读 Tool::getCode）
     */
    private static class FakeTool implements Tool {
        private final String code;

        FakeTool(String code) {
            this.code = code;
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
            return "";
        }
    }

    private static Map<String, Object> find(List<Map<String, Object>> list, String sessionId) {
        for (Map<String, Object> item : list) {
            if (sessionId.equals(item.get("sessionId"))) {
                return item;
            }
        }
        return null;
    }

    private static List<String> kinds(List<Map<String, Object>> steps) {
        List<String> kinds = new ArrayList<>();
        for (Map<String, Object> step : steps) {
            kinds.add(String.valueOf(step.get("kind")));
        }
        return kinds;
    }

    private static Map<String, Object> firstStep(List<Map<String, Object>> steps, String kind) {
        Map<String, Object> empty = new LinkedHashMap<>();
        for (Map<String, Object> step : steps) {
            if (kind.equals(step.get("kind"))) {
                return step;
            }
        }
        return empty;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castList(Object value) {
        return value instanceof List ? (List<Map<String, Object>>) value : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    private static long num(Object value) {
        return value instanceof Number n ? n.longValue() : -1L;
    }

    private static int count(String text, String needle) {
        int hits = 0;
        int from = 0;
        while (true) {
            int idx = text.indexOf(needle, from);
            if (idx < 0) {
                return hits;
            }
            hits++;
            from = idx + needle.length();
        }
    }

    private static String head(String text) {
        if (text == null) {
            return "null";
        }
        return text.length() > 260 ? text.substring(0, 260) + "…" : text;
    }

    private static void check(String name, boolean ok) {
        check(name, ok, null);
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            PASSED.incrementAndGet();
            log.info("  ✓ {}", name);
        } else {
            FAILED.incrementAndGet();
            log.error("  ✗ {}{}", name, detail == null ? "" : " -> " + detail);
        }
    }

    private static void fail(String name, Throwable e) {
        FAILED.incrementAndGet();
        log.error("  ✗ {} 抛异常", name, e);
    }
}
