package com.szh.tool.tools.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.szh.memory.LongTermMemory;
import com.szh.memory.Memory;
import com.szh.memory.MemoryScope;
import com.szh.tool.ToolContext;
import com.szh.tool.ToolDefinition;

import java.util.List;

/**
 * 记忆检索工具：让模型主动按自然语言/关键词从长期记忆中召回相关内容。
 * <p>
 * 对齐 Letta/MemGPT 的 archival_memory_search 与 Claude Code 的 memory 检索能力。
 * 与「每轮自动注入」互补：当模型判断需要更早、更宽或特定作用域的记忆时，可显式检索。
 * 底层复用 SQLite FTS5（bm25 排序 + LIKE 子串兜底）。
 *
 * @author demussong
 * @date 2026/9/21
 */
public class RecallTool extends MemoryToolSupport {

    public static final String CODE = "recall";

    private static final int DEFAULT_LIMIT = 5;

    private static final ToolDefinition TOOL_DEFINITION = ToolDefinition.builder()
            .name("recall")
            .code(CODE)
            .type("system")
            .description("从长期记忆中检索与 query 相关的记忆，返回按相关度排序的若干条（含分类、作用域、标题、正文、关键词）。"
                    + "当你需要此前会话中沉淀的用户偏好、项目事实、经验或决策，而当前上下文没有时使用。")
            .parameters("{\"type\":\"object\",\"properties\":{"
                    + "\"query\":{\"type\":\"string\",\"description\":\"检索词，自然语言或关键词均可\"},"
                    + "\"scope\":{\"type\":\"string\",\"description\":\"可选，限定作用域 GLOBAL/PROJECT/SESSION；不传则不限\"},"
                    + "\"limit\":{\"type\":\"integer\",\"description\":\"返回条数上限，默认 5\"}"
                    + "},\"required\":[\"query\"]}")
            .build();

    @Override
    public String getCode() {
        return CODE;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return TOOL_DEFINITION;
    }

    /**
     * 检索结果就是模型要读的正文，必须直接内联回传
     */
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
        String query = text(args, "query");
        if (query == null) {
            return "缺少必填参数 query";
        }
        MemoryScope scope = parseScope(text(args, "scope"));
        int limit = integer(args, "limit", DEFAULT_LIMIT);

        List<Memory> hits = memory.recall(query, scope, limit);
        if (hits.isEmpty()) {
            return "未检索到与「" + query + "」相关的长期记忆。";
        }
        return memory.format(hits);
    }

    /**
     * 作用域解析：不传/空白返回 null（表示不限作用域），否则宽松解析
     */
    private MemoryScope parseScope(String raw) {
        return raw == null || raw.isBlank() ? null : MemoryScope.from(raw);
    }
}
