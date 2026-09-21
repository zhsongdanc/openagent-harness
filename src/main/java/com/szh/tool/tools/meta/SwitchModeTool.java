package com.szh.tool.tools.meta;

import com.fasterxml.jackson.databind.JsonNode;
import com.szh.event.ModeSwitchedEvent;
import com.szh.tool.ToolContext;
import com.szh.tool.ToolDefinition;
import lombok.extern.slf4j.Slf4j;

/**
 * 模式切换工具（对标 Claude Code SwitchMode）：在 NORMAL（普通，全部工具、边想边改）与
 * PLAN（规划，只读工具子集 + 规划指令、先出方案再执行）之间切换。
 * <p>
 * 生效时机：模式是<b>会话级</b>状态（{@link AgentModeStore}），运行时每轮开头读取——本工具切换后，
 * <b>下一轮</b>模型调用即按新模式收敛工具集与注入指令，无需重启会话。约束的真正落点在
 * {@link PlanModePolicy}（工具过滤 + system prompt 追加），本工具只负责改状态 + 落 {@link ModeSwitchedEvent} 留痕。
 * <p>
 * 用户也可在 REPL 用 {@code /mode plan|normal} 直接切换；两条路径写同一份 {@link AgentModeStore}。
 *
 * @author demussong
 * @date 2026/9/22
 */
@Slf4j
public class SwitchModeTool extends MetaToolSupport {

    public static final String CODE = "switch_mode";

    private static final ToolDefinition TOOL_DEFINITION = ToolDefinition.builder()
            .name(CODE)
            .code(CODE)
            .type("system")
            .description("切换 agent 的运行模式。plan=规划模式：禁用写入/提交/构建类工具，只保留只读检索，"
                    + "让你先探索代码库、产出决策完备的实现方案并与用户确认，而不直接动手改；"
                    + "normal=普通模式：恢复全部工具、可执行改动。"
                    + "适用场景：面对大范围重构、有多种方案取舍、或用户要求「先给方案别改代码」时切到 plan；"
                    + "方案确认后再切回 normal 执行。不传 mode 则只回报当前模式。切换在下一轮生效。")
            .parameters("{\"type\":\"object\",\"properties\":{"
                    + "\"mode\":{\"type\":\"string\",\"enum\":[\"plan\",\"normal\"],"
                    + "\"description\":\"目标模式；省略则查询当前模式\"}"
                    + "}}")
            .build();

    @Override
    public String getCode() {
        return CODE;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return TOOL_DEFINITION;
    }

    @Override
    public String execute(ToolContext toolContext) {
        JsonNode args = parseArgs(toolContext.getArgs());
        String sessionId = toolContext.getSessionId();
        AgentModeStore store = AgentModeStore.get();

        String requested = text(args, "mode");
        if (requested == null) {
            return "当前模式：" + store.get(sessionId).label() + "。可传 mode=plan|normal 切换。";
        }

        AgentMode target = AgentMode.from(requested);
        AgentMode previous = store.get(sessionId);
        store.set(sessionId, target);
        emit(toolContext, new ModeSwitchedEvent(sessionId, toolContext.getRunId(),
                toolContext.getTurnId(), toolContext.getRound(), target.name()));
        log.info("switch_mode: session={}, {} -> {}", sessionId, previous, target);

        if (target == previous) {
            return "已处于 " + target.label() + "，无需切换。";
        }
        if (target == AgentMode.PLAN) {
            return "已切换到 " + target.label() + "。从下一轮起写入/提交/构建类工具将被禁用，"
                    + "请专注探索代码库并产出实现方案，待用户确认后再切回 normal 执行。";
        }
        return "已切换到 " + target.label() + "。从下一轮起全部工具恢复可用，可以开始执行改动。";
    }
}
