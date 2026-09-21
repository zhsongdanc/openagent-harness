package com.szh.tool.tools.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.szh.memory.LongTermMemory;
import com.szh.memory.MemoryScope;
import com.szh.tool.ToolContext;
import com.szh.tool.ToolDefinition;

/**
 * 记忆遗忘工具：让模型主动删除过时、错误或不再适用的长期记忆。
 * <p>
 * 支持两种模式：按 id 精确删除，或按检索词批量删除命中的记忆（可限定作用域）。
 * 这是记忆生命周期管理的关键一环——只有能「忘」，记忆库才不会随时间累积矛盾与噪声。
 *
 * @author demussong
 * @date 2026/9/21
 */
public class ForgetTool extends MemoryToolSupport {

    public static final String CODE = "forget";

    private static final int DEFAULT_LIMIT = 5;

    private static final ToolDefinition TOOL_DEFINITION = ToolDefinition.builder()
            .name("forget")
            .code(CODE)
            .type("system")
            .description("从长期记忆中删除条目。传 id 精确删除一条；或传 query 删除检索命中的记忆（可用 scope 限定作用域、limit 限定条数）。"
                    + "当某条记忆已过时、被更正或不再适用时使用。返回实际删除的条数。")
            .parameters("{\"type\":\"object\",\"properties\":{"
                    + "\"id\":{\"type\":\"string\",\"description\":\"要删除的记忆 id（来自 remember/recall 的返回）\"},"
                    + "\"query\":{\"type\":\"string\",\"description\":\"未提供 id 时，按检索词删除命中的记忆\"},"
                    + "\"scope\":{\"type\":\"string\",\"description\":\"可选，按 query 删除时限定作用域 GLOBAL/PROJECT/SESSION\"},"
                    + "\"limit\":{\"type\":\"integer\",\"description\":\"按 query 删除时的条数上限，默认 5\"}"
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
    public boolean inlineResult() {
        return true;
    }

    @Override
    public String execute(ToolContext toolContext) {
        LongTermMemory memory = LongTermMemory.get();
        if (!memory.isActive()) {
            return DISABLED_MSG;
        }
        JsonNode args = parseArgs(toolContext.getArgs());

        String id = text(args, "id");
        if (id != null) {
            return memory.forget(id) ? "已删除记忆 id=" + id : "删除失败或记忆不存在：id=" + id;
        }

        String query = text(args, "query");
        if (query == null) {
            return "请提供 id 或 query 之一";
        }
        MemoryScope scope = parseScope(text(args, "scope"));
        int limit = integer(args, "limit", DEFAULT_LIMIT);
        int removed = memory.forgetByQuery(query, scope, limit);
        return removed > 0
                ? "已删除 " + removed + " 条与「" + query + "」相关的记忆。"
                : "未找到与「" + query + "」相关的记忆，无删除。";
    }

    private MemoryScope parseScope(String raw) {
        return raw == null || raw.isBlank() ? null : MemoryScope.from(raw);
    }
}
