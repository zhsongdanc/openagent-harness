package com.szh.tool.tools.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.szh.tool.Tool;
import com.szh.utils.JsonUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 记忆类工具的公共基类：统一 JSON 入参解析与「记忆未启用」的降级回执。
 * <p>
 * 对齐主流 harness 的记忆工具范式（Letta/MemGPT 的 archival_memory_insert/search、
 * Claude Code 的 memory tool）——把长期记忆的读写删暴露成模型可主动调用的工具，
 * 而不是只靠被动的反思抽取与自动注入。
 *
 * @author demussong
 * @date 2026/9/21
 */
public abstract class MemoryToolSupport implements Tool {

    protected static final String DISABLED_MSG = "长期记忆未启用（memory.enabled=false 或 SQLite 存储未就绪），本次操作被忽略。";

    /**
     * 解析工具入参为 JsonNode，非法/空时返回空对象节点，避免 NPE
     */
    protected JsonNode parseArgs(String args) {
        String trimmed = args == null ? "" : args.trim();
        JsonNode node = trimmed.startsWith("{") ? JsonUtil.readTree(trimmed) : null;
        return node != null && node.isObject() ? node : JsonUtil.getMapper().createObjectNode();
    }

    protected String text(JsonNode args, String field) {
        JsonNode value = args.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText().trim();
        return text.isEmpty() ? null : text;
    }

    protected int integer(JsonNode args, String field, int defaultValue) {
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

    /**
     * 读取字符串数组字段，兼容「数组」与「逗号分隔字符串」两种写法
     */
    protected List<String> stringArray(JsonNode args, String field) {
        List<String> result = new ArrayList<>();
        JsonNode value = args.get(field);
        if (value == null || value.isNull()) {
            return result;
        }
        if (value.isArray()) {
            for (JsonNode item : value) {
                if (item != null && !item.isNull()) {
                    String s = item.asText().trim();
                    if (!s.isEmpty()) {
                        result.add(s);
                    }
                }
            }
        } else {
            for (String part : value.asText().split(",")) {
                String s = part.trim();
                if (!s.isEmpty()) {
                    result.add(s);
                }
            }
        }
        return result;
    }
}
