package com.szh.test;

import com.szh.agent.AgentState;
import com.szh.agent.subagent.SubAgentExecutor;
import com.szh.agent.subagent.SubAgentType;
import com.szh.context.dto.AssistantMessageItem;
import com.szh.context.dto.MessageItem;
import com.szh.event.Event;
import com.szh.model.Model;
import com.szh.model.dto.ActionEnum;
import com.szh.model.dto.ModelResp;
import com.szh.store.MemoryEventStore;
import com.szh.tool.Tool;
import com.szh.tool.ToolContext;
import com.szh.tool.ToolRegistry;
import com.szh.tool.tools.subagent.DispatchSubAgentTool;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 子 agent / Task 派生能力冒烟验证（无测试框架，按项目惯例 main 直跑，注入 Fake 模型不依赖真实 API）：
 * <ol>
 *   <li>类型解析：SubAgentType.from 宽松回退 + catalog 清单；</li>
 *   <li>工具白名单：只读型子 agent 拿不到写工具，且子 registry 不含 dispatch_subagent（深度闸门）；</li>
 *   <li>深度闸门：超 maxDepth 的派生被拒绝；</li>
 *   <li>端到端派生：子 agent 返回结论，且中间事件不污染父 session（上下文物理隔离）；</li>
 *   <li>工具入参校验：dispatch_subagent 缺 prompt 时报错。</li>
 * </ol>
 *
 * @author demussong
 * @date 2026/9/22
 */
@Slf4j
public class SubAgentSmokeTest {

    private static final AtomicInteger PASSED = new AtomicInteger();
    private static final AtomicInteger FAILED = new AtomicInteger();

    public static void main(String[] args) {
        // 冒烟用进程内事件存储，关掉长期记忆/沙箱交互/MCP 拉起，避免阻塞 stdin 或触达真实模型 API
        System.setProperty("store.engine", "MEMORY");
        System.setProperty("memory.enabled", "false");
        System.setProperty("memory.tools.enabled", "false");
        System.setProperty("shell.security.enabled", "false");
        System.setProperty("model.stream.enabled", "false");
        System.setProperty("mcp.enabled", "false");
        System.setProperty("subagent.enabled", "true");
        System.setProperty("subagent.maxDepth", "1");
        System.setProperty("subagent.runtime", "chat");

        testTypeParsing();
        testToolWhitelist();
        testDepthGuard();
        testEndToEndDispatch();
        testToolArgValidation();

        log.info("====== 子 agent 冒烟结果: passed={}, failed={} ======", PASSED.get(), FAILED.get());
        if (FAILED.get() > 0) {
            System.exit(1);
        }
    }

    /**
     * 类型解析：已知类型精确命中，未知/空回退 general-purpose；catalog 列出全部类型
     */
    private static void testTypeParsing() {
        check("code-review 精确解析", SubAgentType.from("code-review") == SubAgentType.CODE_REVIEW);
        check("大小写/空白宽松解析", SubAgentType.from("  EXPLORE ") == SubAgentType.EXPLORE);
        check("未知类型回退 general-purpose", SubAgentType.from("not-exist") == SubAgentType.GENERAL_PURPOSE);
        check("空类型回退 general-purpose", SubAgentType.from(null) == SubAgentType.GENERAL_PURPOSE);
        String catalog = SubAgentType.catalog();
        check("catalog 含全部类型",
                catalog.contains("general-purpose") && catalog.contains("code-review") && catalog.contains("explore"));
        check("code-review 白名单为只读子集", SubAgentType.CODE_REVIEW.allowedCodes() != null
                && SubAgentType.CODE_REVIEW.allowedCodes().contains("read_file"));
        check("general-purpose 白名单为 null（全部工具）", SubAgentType.GENERAL_PURPOSE.allowedCodes() == null);
    }

    /**
     * 工具白名单 + 深度闸门：只读子 agent 无写工具；子 registry（depth>=maxDepth）不含 dispatch_subagent；
     * 主 registry（depth 0）含 dispatch_subagent
     */
    private static void testToolWhitelist() {
        ToolRegistry review = new ToolRegistry(SubAgentType.CODE_REVIEW.allowedCodes(), 1);
        check("code-review 子 agent 有 read_file", review.getToolByCode("read_file") != null);
        check("code-review 子 agent 无 write_file", review.getToolByCode("write_file") == null);
        check("code-review 子 agent 无 edit_file", review.getToolByCode("edit_file") == null);
        check("code-review 子 agent 无 git_commit", review.getToolByCode("git_commit") == null);
        check("子 registry（depth=1>=maxDepth）不含 dispatch_subagent",
                review.getToolByCode(DispatchSubAgentTool.CODE) == null);

        ToolRegistry main = new ToolRegistry(null, 0);
        check("主 registry（depth=0）含 dispatch_subagent",
                main.getToolByCode(DispatchSubAgentTool.CODE) != null);
        check("主 registry 含 write_file（全量工具）", main.getToolByCode("write_file") != null);

        ToolRegistry generalChild = new ToolRegistry(null, 1);
        check("general-purpose 子 registry 保留写工具", generalChild.getToolByCode("write_file") != null);
        check("general-purpose 子 registry 仍无 dispatch_subagent（深度闸门）",
                generalChild.getToolByCode(DispatchSubAgentTool.CODE) == null);
    }

    /**
     * 深度闸门：parentDepth 使 childDepth 超 maxDepth 时直接拒绝，不真正派生
     */
    private static void testDepthGuard() {
        SubAgentExecutor executor = new SubAgentExecutor(new FakeSubAgentModel(), null);
        String refused = executor.dispatch("general-purpose", "任意任务", "parent", 5);
        check("超深度派生被拒绝", refused.contains("深度") && !refused.contains(FakeSubAgentModel.CONCLUSION));
    }

    /**
     * 端到端派生：子 agent 返回结论，父 session 事件流不被子 agent 中间事件污染（上下文物理隔离）
     */
    private static void testEndToEndDispatch() {
        SubAgentExecutor executor = new SubAgentExecutor(new FakeSubAgentModel(), null);
        MemoryEventStore parentStore = new MemoryEventStore();
        AgentState parentState = new AgentState(parentStore);
        String parentSession = "parent-session";

        String result = executor.dispatch("general-purpose", "帮我在独立上下文里完成一段探索", parentSession, 0);
        check("子 agent 返回最终结论", result != null && result.contains(FakeSubAgentModel.CONCLUSION));

        // 父 AgentState 除构造时的 system prompt 外未新增任何事件，证明子 agent 事件完全隔离
        List<Event> parentEvents = parentState.getEventStore().getEvents(parentSession);
        check("子 agent 事件不污染父 session（物理隔离）", parentEvents.isEmpty());
    }

    /**
     * 工具入参校验：缺 prompt 报错；带 prompt 经注入的 executor 返回结论
     */
    private static void testToolArgValidation() {
        DispatchSubAgentTool tool = new DispatchSubAgentTool(0, new SubAgentExecutor(new FakeSubAgentModel(), null));
        String missing = tool.execute(new ToolContext("s", "r", null, "{\"type\":\"explore\"}"));
        check("dispatch_subagent 缺 prompt 时报错", missing.contains("prompt"));

        String ok = tool.execute(new ToolContext("s", "r", null,
                "{\"type\":\"explore\",\"prompt\":\"定位鉴权逻辑实现\"}"));
        check("dispatch_subagent 带 prompt 返回子 agent 结论", ok.contains(FakeSubAgentModel.CONCLUSION));

        check("dispatch_subagent 默认走落盘策略（inlineResult=false）", !tool.inlineResult());
    }

    /**
     * 假模型：任何输入都立即给出最终结论，不调用工具，用于脱离真实 API 验证派生链路
     */
    private static class FakeSubAgentModel implements Model {

        static final String CONCLUSION = "子 agent 最终结论：鉴权逻辑位于 AuthController，已完成梳理";

        @Override
        public ModelResp call(List<MessageItem> messages, List<Tool> tools) {
            return new ModelResp(new AssistantMessageItem(CONCLUSION), ActionEnum.FINAL_ANSWER);
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
