package com.szh.test;

import com.szh.agent.AgentState;
import com.szh.event.Event;
import com.szh.event.EventEnum;
import com.szh.event.ModeSwitchedEvent;
import com.szh.event.TodoUpdatedEvent;
import com.szh.store.EventJsonCodec;
import com.szh.store.MemoryEventStore;
import com.szh.store.SerializedEvent;
import com.szh.tool.Tool;
import com.szh.tool.ToolContext;
import com.szh.tool.ToolRegistry;
import com.szh.tool.tools.meta.AgentMode;
import com.szh.tool.tools.meta.AgentModeStore;
import com.szh.tool.tools.meta.AskUserQuestionTool;
import com.szh.tool.tools.meta.ConsoleQuestionPrompter;
import com.szh.tool.tools.meta.PlanModePolicy;
import com.szh.tool.tools.meta.Question;
import com.szh.tool.tools.meta.SwitchModeTool;
import com.szh.tool.tools.meta.TodoItem;
import com.szh.tool.tools.meta.TodoStore;
import com.szh.tool.tools.meta.TodoWriteTool;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 元工具（todo_write / ask_user_question / switch_mode）冒烟验证（无测试框架，按项目惯例 main 直跑，
 * 注入 Fake 问询器与进程内事件存储，不依赖真实模型 API / stdin）：
 * <ol>
 *   <li>todo_write：整体替换 / merge 增量、状态归一化、TodoStore 渲染、TodoUpdatedEvent 落库；</li>
 *   <li>事件编解码：TodoUpdatedEvent / ModeSwitchedEvent 经 EventJsonCodec 往返不失真；</li>
 *   <li>ask_user_question：入参解析、结构化回执、非交互兜底；</li>
 *   <li>switch_mode + PlanModePolicy：模式写入 AgentModeStore，PLAN 下剔除写入类工具、保留只读与元工具。</li>
 * </ol>
 *
 * @author demussong
 * @date 2026/9/22
 */
@Slf4j
public class MetaSmokeTest {

    private static final AtomicInteger PASSED = new AtomicInteger();
    private static final AtomicInteger FAILED = new AtomicInteger();

    public static void main(String[] args) {
        // 冒烟用进程内事件存储，关掉长期记忆/沙箱/MCP，避免阻塞 stdin 或触达真实模型 API
        System.setProperty("store.engine", "MEMORY");
        System.setProperty("memory.enabled", "false");
        System.setProperty("memory.tools.enabled", "false");
        System.setProperty("shell.security.enabled", "false");
        System.setProperty("model.stream.enabled", "false");
        System.setProperty("mcp.enabled", "false");
        System.setProperty("meta.tools.enabled", "true");

        testTodoWrite();
        testEventCodecRoundTrip();
        testAskUserQuestion();
        testSwitchModeAndPlanPolicy();
        testRegistryWiring();

        log.info("====== 元工具冒烟结果: passed={}, failed={} ======", PASSED.get(), FAILED.get());
        if (FAILED.get() > 0) {
            System.exit(1);
        }
    }

    /**
     * todo_write：整体替换 -> merge 增量更新状态 -> 渲染与事件落库
     */
    private static void testTodoWrite() {
        MemoryEventStore store = new MemoryEventStore();
        AgentState state = new AgentState(store);
        String session = "meta-todo-session";
        TodoStore.get().clear(session);
        TodoWriteTool tool = new TodoWriteTool();

        String first = tool.execute(ctx(state, session,
                "{\"todos\":[{\"id\":\"a\",\"content\":\"分析需求\",\"status\":\"in_progress\"},"
                        + "{\"id\":\"b\",\"content\":\"实现功能\",\"status\":\"pending\"}]}"));
        check("todo_write 回执含项数", first.contains("2 项"));
        check("TodoStore 记录两项", TodoStore.get().list(session).size() == 2);
        check("状态宽松归一化 in_progress->IN_PROGRESS",
                TodoStore.get().list(session).get(0).safeStatus().name().equals("IN_PROGRESS"));
        check("渲染含完成度分母", TodoStore.get().render(session).contains("/2 完成"));

        // merge=true 只更新 a 的状态、追加 c，b 保留
        String merged = tool.execute(ctx(state, session,
                "{\"merge\":true,\"todos\":[{\"id\":\"a\",\"content\":\"分析需求\",\"status\":\"COMPLETE\"},"
                        + "{\"id\":\"c\",\"content\":\"写测试\",\"status\":\"PENDING\"}]}"));
        List<TodoItem> items = TodoStore.get().list(session);
        check("merge 后共三项", items.size() == 3);
        check("merge 命中既有项更新状态", items.get(0).safeStatus() == com.szh.tool.tools.meta.TodoStatus.COMPLETE);
        check("merge 追加新项 c", items.stream().anyMatch(i -> "c".equals(i.getId())));
        check("merge 保留未提及项 b", items.stream().anyMatch(i -> "b".equals(i.getId())));
        check("merge 回执含项数", merged.contains("3 项"));

        // 事件落库：应有 2 条 TodoUpdatedEvent（非 MessageEvent，不进 modelContext）
        List<Event> events = store.getEvents(session);
        long todoEvents = events.stream().filter(e -> e.getType() == EventEnum.TODO_UPDATED).count();
        check("TodoUpdatedEvent 落库两条", todoEvents == 2);
        check("TodoUpdatedEvent 不进 modelContext（仅 system prompt）",
                state.getModelContext().size() == 1);

        // 缺 todos 报错
        check("todo_write 缺 todos 报错",
                tool.execute(ctx(state, session, "{}")).contains("todos"));
    }

    /**
     * 事件编解码往返：TodoUpdatedEvent / ModeSwitchedEvent 经 serialize->deserialize 后关键字段不失真
     */
    private static void testEventCodecRoundTrip() {
        TodoUpdatedEvent todoEvent = new TodoUpdatedEvent("s", "r", "t", 2,
                List.of(new TodoItem("a", "分析需求", com.szh.tool.tools.meta.TodoStatus.COMPLETE)));
        SerializedEvent s1 = EventJsonCodec.serialize(todoEvent);
        Event back1 = EventJsonCodec.deserialize(s1);
        check("TodoUpdatedEvent 往返类型正确", back1 instanceof TodoUpdatedEvent);
        check("TodoUpdatedEvent 往返保留清单内容",
                back1 instanceof TodoUpdatedEvent t && t.getTodos().size() == 1
                        && "分析需求".equals(t.getTodos().get(0).getContent())
                        && t.getTodos().get(0).safeStatus() == com.szh.tool.tools.meta.TodoStatus.COMPLETE);

        ModeSwitchedEvent modeEvent = new ModeSwitchedEvent("s", "r", "t", 0, "PLAN");
        Event back2 = EventJsonCodec.deserialize(EventJsonCodec.serialize(modeEvent));
        check("ModeSwitchedEvent 往返保留模式",
                back2 instanceof ModeSwitchedEvent m && "PLAN".equals(m.getMode()));
    }

    /**
     * ask_user_question：注入 Fake 问询器验证解析与结构化回执；null 应答走非交互兜底
     */
    private static void testAskUserQuestion() {
        AskUserQuestionTool tool = new AskUserQuestionTool(
                new FakePrompter(List.of("方案A")));
        String answered = tool.execute(new ToolContext("s", "r", null,
                "{\"questions\":[{\"question\":\"选哪个方案？\",\"header\":\"方案\","
                        + "\"options\":[{\"label\":\"方案A\",\"description\":\"快\"},{\"label\":\"方案B\"}]}]}"));
        check("ask_user_question 回执含用户答案", answered.contains("方案A"));
        check("ask_user_question 回执含原问题", answered.contains("选哪个方案"));

        AskUserQuestionTool nonInteractive = new AskUserQuestionTool(new FakePrompter(null));
        String fallback = nonInteractive.execute(new ToolContext("s", "r", null,
                "{\"questions\":[{\"question\":\"q\",\"options\":[{\"label\":\"x\"},{\"label\":\"y\"}]}]}"));
        check("非交互环境返回兜底提示", fallback.contains("非交互"));

        check("ask_user_question 缺 options 报错",
                tool.execute(new ToolContext("s", "r", null, "{\"questions\":[]}")).contains("缺少有效的问题"));
        check("元工具回执内联（inlineResult=true）", tool.inlineResult());
    }

    /**
     * switch_mode + PlanModePolicy：切 PLAN 后写入类工具被剔除，只读与元工具保留；切回 NORMAL 全量恢复
     */
    private static void testSwitchModeAndPlanPolicy() {
        MemoryEventStore store = new MemoryEventStore();
        AgentState state = new AgentState(store);
        String session = "meta-mode-session";
        AgentModeStore.get().clear(session);
        SwitchModeTool tool = new SwitchModeTool();

        check("默认 NORMAL 模式", AgentModeStore.get().get(session) == AgentMode.NORMAL);
        tool.execute(ctx(state, session, "{\"mode\":\"plan\"}"));
        check("switch_mode 写入 PLAN", AgentModeStore.get().isPlan(session));
        check("ModeSwitchedEvent 落库",
                store.getEvents(session).stream().anyMatch(e -> e.getType() == EventEnum.MODE_SWITCHED));

        List<Tool> all = new ToolRegistry(null, 0).getTools();
        List<Tool> planTools = PlanModePolicy.effectiveTools(all, session);
        check("PLAN 剔除 write_file", planTools.stream().noneMatch(t -> t.getCode().equals("write_file")));
        check("PLAN 剔除 git_commit", planTools.stream().noneMatch(t -> t.getCode().equals("git_commit")));
        check("PLAN 剔除 dispatch_subagent", planTools.stream().noneMatch(t -> t.getCode().equals("dispatch_subagent")));
        check("PLAN 保留 read_file", planTools.stream().anyMatch(t -> t.getCode().equals("read_file")));
        check("PLAN 保留 todo_write", planTools.stream().anyMatch(t -> t.getCode().equals("todo_write")));

        tool.execute(ctx(state, session, "{\"mode\":\"normal\"}"));
        check("切回 NORMAL", !AgentModeStore.get().isPlan(session));
        check("NORMAL 工具全量（含 write_file）",
                PlanModePolicy.effectiveTools(all, session).stream().anyMatch(t -> t.getCode().equals("write_file")));
    }

    /**
     * ToolRegistry 装配：三件套均注册进主 registry
     */
    private static void testRegistryWiring() {
        ToolRegistry registry = new ToolRegistry(null, 0);
        check("registry 注册 todo_write", registry.getToolByCode(TodoWriteTool.CODE) != null);
        check("registry 注册 ask_user_question", registry.getToolByCode(AskUserQuestionTool.CODE) != null);
        check("registry 注册 switch_mode", registry.getToolByCode(SwitchModeTool.CODE) != null);
    }

    /**
     * 构造带 AgentState + 轮次信息的 ToolContext，模拟运行时（ParallelToolExecutor）的注入
     */
    private static ToolContext ctx(AgentState state, String session, String argsJson) {
        ToolContext ctx = new ToolContext(session, "run-1", null, argsJson);
        ctx.setAgentState(state);
        ctx.setTurnId("turn_1");
        ctx.setRound(1);
        return ctx;
    }

    /**
     * 假问询器：返回预置答案（null 模拟非交互 EOF），脱离真实 stdin 验证 ask_user_question 链路
     */
    private static class FakePrompter extends ConsoleQuestionPrompter {
        private final List<String> canned;

        FakePrompter(List<String> canned) {
            this.canned = canned;
        }

        @Override
        public synchronized List<String> ask(List<Question> questions) {
            return canned;
        }
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
}
