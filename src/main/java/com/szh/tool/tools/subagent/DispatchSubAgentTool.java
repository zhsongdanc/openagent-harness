package com.szh.tool.tools.subagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.szh.agent.subagent.SubAgentExecutor;
import com.szh.agent.subagent.SubAgentType;
import com.szh.tool.Tool;
import com.szh.tool.ToolContext;
import com.szh.tool.ToolDefinition;
import com.szh.utils.JsonUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 子 agent 派生工具（对标 Claude Code 的 Task 工具）：主 agent 通过
 * {@code dispatch_subagent(type, prompt)} 把一段可独立完成、会产生大量中间产物的子任务外包给子 agent，
 * 子 agent 在<b>独立上下文窗口 + 独立工具白名单</b>中跑完，主 agent 只拿最终结论——这是长任务不炸上下文的核心手段。
 * <p>
 * 结果呈现走既有落盘策略（{@link #inlineResult()} 默认 false）：结论超长时自动 spill 成引用存根，
 * 与其它工具一致，避免子 agent 的长结论反过来撑爆主上下文。
 * <p>
 * 深度控制：实例持有 {@code depth}（主 agent 注册的实例 depth=0），派生子 agent 时子 registry 以 depth+1 构造；
 * 达到 {@code subagent.maxDepth} 后子 registry 不再注册本工具，从结构上杜绝无限递归（见 ToolRegistry / SubAgentExecutor）。
 *
 * @author demussong
 * @date 2026/9/22
 */
@Slf4j
public class DispatchSubAgentTool implements Tool {

    public static final String CODE = "dispatch_subagent";

    private static final ToolDefinition TOOL_DEFINITION = ToolDefinition.builder()
            .name(CODE)
            .code(CODE)
            .type("system")
            .description("派生一个子 agent 在独立上下文中完成子任务，只返回其最终结论。"
                    + "适用场景：需要大范围翻代码/多次试错/会产生大量中间产物、但主对话只需要一个结论的任务"
                    + "（如代码审查、代码库探索、独立实现子任务），用它可避免中间产物挤爆主上下文。"
                    + "子 agent 看不到当前对话历史，prompt 必须自包含（把必要的背景、文件路径、目标都写进去）。"
                    + "可用 type：" + SubAgentType.catalog() + "。不传 type 默认 general-purpose。")
            .parameters("{\"type\":\"object\",\"properties\":{"
                    + "\"type\":{\"type\":\"string\",\"description\":\"子 agent 类型，可选 general-purpose/code-review/explore，默认 general-purpose\"},"
                    + "\"prompt\":{\"type\":\"string\",\"description\":\"交给子 agent 的完整任务指令，必须自包含（子 agent 看不到主对话上下文）\"}"
                    + "},\"required\":[\"prompt\"]}")
            .build();

    /**
     * 当前工具实例所处的派生深度：主 agent 注册的为 0，子 agent registry 里的为 1、2……
     */
    private final int depth;

    private final SubAgentExecutor executor;

    public DispatchSubAgentTool() {
        this(0, new SubAgentExecutor());
    }

    public DispatchSubAgentTool(int depth) {
        this(depth, new SubAgentExecutor());
    }

    /**
     * 测试构造：注入 {@link SubAgentExecutor}（可携带 Fake 模型），脱离真实模型 API 验证派生链路。
     */
    public DispatchSubAgentTool(int depth, SubAgentExecutor executor) {
        this.depth = depth;
        this.executor = executor;
    }

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
        String prompt = text(args, "prompt");
        if (prompt == null) {
            return "缺少必填参数 prompt：请把交给子 agent 的完整任务指令写进 prompt（子 agent 看不到主对话上下文）。";
        }
        String type = text(args, "type");
        log.info("dispatch_subagent invoked: type={}, depth={}, promptLen={}",
                type == null ? "general-purpose" : type, depth, prompt.length());
        return executor.dispatch(type, prompt, toolContext.getSessionId(), depth);
    }

    private JsonNode parseArgs(String args) {
        String trimmed = args == null ? "" : args.trim();
        JsonNode node = trimmed.startsWith("{") ? JsonUtil.readTree(trimmed) : null;
        return node != null && node.isObject() ? node : JsonUtil.getMapper().createObjectNode();
    }

    private String text(JsonNode args, String field) {
        JsonNode value = args.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText().trim();
        return text.isEmpty() ? null : text;
    }
}
