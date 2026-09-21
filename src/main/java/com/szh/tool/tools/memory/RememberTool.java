package com.szh.tool.tools.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.szh.memory.LongTermMemory;
import com.szh.memory.Memory;
import com.szh.memory.MemoryCategory;
import com.szh.memory.MemoryScope;
import com.szh.tool.ToolContext;
import com.szh.tool.ToolDefinition;

/**
 * 记忆写入工具：让模型主动把「值得跨会话保留」的信息写入长期记忆。
 * <p>
 * 对齐 Letta/MemGPT 的 archival_memory_insert 与 Claude Code 的 memory 写入能力——
 * 相比只在 run 结束后被动反思抽取，模型可在对话中随时显式落库关键事实/偏好/决策。
 * 写入经由 {@link LongTermMemory#remember}，自带去重（近似则更新既有记忆）与容量淘汰。
 *
 * @author demussong
 * @date 2026/9/21
 */
public class RememberTool extends MemoryToolSupport {

    public static final String CODE = "remember";

    private static final ToolDefinition TOOL_DEFINITION = ToolDefinition.builder()
            .name("remember")
            .code(CODE)
            .type("system")
            .description("把一条值得跨会话长期保留的信息写入长期记忆（用户偏好、项目事实、开发规范、经验教训、踩坑、工程决策等）。"
                    + "写入会自动去重：若已存在高度相似的同分类记忆，则更新它而非新增。"
                    + "content 必填且应自包含（脱离本次对话也能看懂）。返回落库的记忆 id，可用于后续 forget。")
            .parameters("{\"type\":\"object\",\"properties\":{"
                    + "\"content\":{\"type\":\"string\",\"description\":\"记忆正文，自包含、陈述句、聚焦单一主题\"},"
                    + "\"title\":{\"type\":\"string\",\"description\":\"5~20 字的简短标题\"},"
                    + "\"category\":{\"type\":\"string\",\"description\":\"分类，枚举：USER_PREFERENCE/PROJECT_INFO/DEVELOPMENT_SPEC/EXPERIENCE/PITFALL/DECISION/OTHER，默认 OTHER\"},"
                    + "\"scope\":{\"type\":\"string\",\"description\":\"作用域，枚举：GLOBAL(跨项目用户级)/PROJECT(项目内)/SESSION(仅本会话)，默认 PROJECT\"},"
                    + "\"keywords\":{\"type\":\"array\",\"items\":{\"type\":\"string\"},\"description\":\"2~6 个便于检索的关键词\"}"
                    + "},\"required\":[\"content\"]}")
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
     * 写入回执是给模型看的确认信息，直接内联，不落盘成存根
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
        String content = text(args, "content");
        if (content == null) {
            return "缺少必填参数 content";
        }

        Memory m = new Memory();
        m.setContent(content);
        m.setTitle(text(args, "title"));
        m.setCategory(MemoryCategory.from(text(args, "category")));
        m.setScope(MemoryScope.from(text(args, "scope")));
        m.setKeywords(stringArray(args, "keywords"));
        m.setSourceSessionId(toolContext.getSessionId());

        String id = memory.remember(m);
        if (id == null) {
            return "记忆写入失败，请稍后重试。";
        }
        return "已记住（id=" + id + "）：" + summarize(m);
    }

    private String summarize(Memory m) {
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(m.getCategory()).append('|').append(m.getScope()).append("] ");
        if (m.getTitle() != null && !m.getTitle().isBlank()) {
            sb.append(m.getTitle()).append(" — ");
        }
        String content = m.getContent().strip();
        sb.append(content.length() > 80 ? content.substring(0, 80) + "…" : content);
        return sb.toString();
    }
}
