package com.szh.tool.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.szh.tool.Tool;
import com.szh.tool.ToolContext;
import com.szh.tool.ToolDefinition;
import com.szh.tool.store.ToolResultStore;
import com.szh.utils.JsonUtil;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.Map;

/**
 * 分页查询工具结果：模型通过此工具按 offset/limit 行号分批查阅存储在文件系统中的工具执行输出。
 * <p>
 * 当工具执行后，完整结果已存入文件，上下文中仅保留 resultId 引用。
 * 模型如需查看完整或部分内容，调用此工具即可分页读取。
 *
 * @author demussong
 * @date 2026/9/17
 */
@Slf4j
public class ReadToolResultTool implements Tool {

    public static final String CODE = "read_tool_result";

    private static final int DEFAULT_LIMIT = 50;

    private static final ToolDefinition TOOL_DEFINITION = ToolDefinition.builder()
            .name("read_tool_result")
            .code(CODE)
            .type("system")
            .description("分页读取工具执行结果。工具执行后完整结果已存入文件，上下文仅保留 resultId 引用，"
                    + "使用此工具按 offset（起始行号，0-based）和 limit（行数）分批查阅。"
                    + "返回内容包含分页元信息（totalLines、returnedLines）以便判断是否还有后续内容。")
            .parameters("{\"type\":\"object\",\"properties\":{"
                    + "\"resultId\":{\"type\":\"string\",\"description\":\"工具结果引用 ID，如工具执行返回的 result_id\"},"
                    + "\"offset\":{\"type\":\"integer\",\"description\":\"起始行号，0-based，默认 0\"},"
                    + "\"limit\":{\"type\":\"integer\",\"description\":\"返回的最大行数，默认 50\"}"
                    + "},\"required\":[\"resultId\"]}")
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
     * read_tool_result 的输出就是模型要读的正文，必须直接内联回传，不能再落盘成存根（否则无限套娃）。
     */
    @Override
    public boolean inlineResult() {
        return true;
    }

    @Override
    public String execute(ToolContext toolContext) {
        JsonNode json = parseArgs(toolContext.getArgs());

        String resultId = text(json, "resultId");
        if (resultId == null) {
            return "缺少必填参数 resultId";
        }
        int offset = integer(json, "offset", 0);
        int limit = integer(json, "limit", DEFAULT_LIMIT);

        Path storeDir = ToolResultStore.resolveStoreDir(toolContext.getWorkspace(), toolContext.getSessionId());
        Map<String, Object> page = ToolResultStore.readPageFrom(storeDir, resultId, offset, limit);

        if (page == null) {
            return "未找到工具结果: resultId=" + resultId;
        }

        int totalLines = (int) page.get("totalLines");
        int returnedLines = (int) page.get("returnedLines");
        String content = (String) page.get("content");

        boolean hasMore = offset + returnedLines < totalLines;
        StringBuilder sb = new StringBuilder();
        sb.append(content);
        sb.append("\n---\n");
        sb.append("行 ").append(offset).append("-").append(offset + returnedLines - 1)
                .append(" / 共 ").append(totalLines).append(" 行");
        if (hasMore) {
            sb.append("（还有 ").append(totalLines - offset - returnedLines).append(" 行，")
                    .append("请用 offset=").append(offset + returnedLines).append(" 继续读取）");
        } else {
            sb.append("（已全部读取）");
        }

        return sb.toString();
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

    private int integer(JsonNode args, String field, int defaultValue) {
        JsonNode value = args.get(field);
        if (value == null || value.isNull()) {
            return defaultValue;
        }
        if (value.isNumber()) {
            return value.asInt(defaultValue);
        }
        try {
            return Integer.parseInt(value.asText().trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
