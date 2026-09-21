package com.szh.tool.tools.meta;

import com.fasterxml.jackson.databind.JsonNode;
import com.szh.agent.AgentState;
import com.szh.event.Event;
import com.szh.tool.Tool;
import com.szh.tool.ToolContext;
import com.szh.utils.JsonUtil;

/**
 * 元工具公共基类：统一 JSON 入参解析与「往事件日志落一条领域事件」的能力。
 * <p>
 * 元工具（todo_write / ask_user_question / switch_mode）与普通工具不同——它们改变的是 agent 的
 * <b>自组织状态</b>（待办清单、运行模式）而非外部世界，这些状态需要以事件形式进日志（唯一真相源），
 * 供 REPL 渲染、{@code /session} 恢复与 TraceReplay 回放。但工具本身只拿得到 {@link ToolContext}，
 * 故约定：运行时在构造 ToolContext 时注入 {@link AgentState}（见 ParallelToolExecutor），
 * 元工具经 {@link #emit(ToolContext, Event)} 把事件落库；无 AgentState（如单测直调）时静默跳过，
 * 内存态 store 仍会更新，保证工具本身可独立测试。
 * <p>
 * 所有元工具输出都是给模型看的简短回执，统一 {@link #inlineResult()} 返回 true，豁免落盘存根机制。
 *
 * @author demussong
 * @date 2026/9/22
 */
public abstract class MetaToolSupport implements Tool {

    /**
     * 元工具回执直接内联回传，不走「落盘 + 引用存根」（回执本就短，落盘反而让模型多绕一次 read_tool_result）
     */
    @Override
    public boolean inlineResult() {
        return true;
    }

    /**
     * 把领域事件落进事件日志；ToolContext 未携带 AgentState（脱离运行时的单测场景）时静默跳过。
     * 落库失败不抛，元工具的主职责是更新内存态并回执模型，事件缺失不该让整个工具调用失败。
     */
    protected void emit(ToolContext ctx, Event event) {
        AgentState state = ctx == null ? null : ctx.getAgentState();
        if (state != null) {
            state.applyEvent(event);
        }
    }

    protected JsonNode parseArgs(String args) {
        String trimmed = args == null ? "" : args.trim();
        JsonNode node = trimmed.startsWith("{") ? JsonUtil.readTree(trimmed) : null;
        return node != null && node.isObject() ? node : JsonUtil.getMapper().createObjectNode();
    }

    protected String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText().trim();
        return text.isEmpty() ? null : text;
    }

    protected boolean bool(JsonNode node, String field, boolean defaultValue) {
        if (node == null) {
            return defaultValue;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return defaultValue;
        }
        if (value.isBoolean()) {
            return value.asBoolean(defaultValue);
        }
        return Boolean.parseBoolean(value.asText().trim());
    }
}
