package com.szh.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.szh.context.compaction.Summarizer;
import com.szh.context.dto.MessageItem;
import com.szh.context.dto.ReasoningMessageItem;
import com.szh.context.dto.SystemMessageItem;
import com.szh.context.dto.UserMessageItem;
import com.szh.utils.CommonUtils;
import com.szh.utils.ConfigUtil;
import com.szh.utils.JsonUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 反思式记忆抽取器（L4 长期记忆的写入来源）。
 * <p>
 * 在一次 run 结束后，把本轮对话交给 LLM 蒸馏成若干条结构化记忆（严格 JSON 数组），
 * 让上下文压缩时被丢弃的信息以“可检索的长期记忆”形式沉淀下来。
 * <p>
 * 复用现有的 {@link Summarizer} 抽象完成一次性文本调用，因此对 Chat Completions
 * 与 Responses API 两种运行时都适用（分别传 ModelSummarizer / ResponseModelSummarizer）。
 * 任何异常/无法解析都返回空列表，绝不阻断主流程。
 *
 * @author demussong
 * @date 2026/9/21
 */
@Slf4j
public class MemoryExtractor {

    private static final String MAX_INPUT_KEY = "memory.reflection.maxInputChars";
    private static final int DEFAULT_MAX_INPUT = 12000;

    /**
     * 抽取指令：要求只输出 JSON 数组，字段与本类解析逻辑严格对应
     */
    private static final String SYSTEM_PROMPT = """
            你是一个记忆抽取器。请从下面的对话中提炼出「值得跨会话长期保留」的记忆，忽略一次性的寒暄、\
            临时中间过程和与本对话强绑定、日后无复用价值的内容。

            只输出一个 JSON 数组，不要输出任何解释或 Markdown 代码块围栏。数组每个元素是一个对象，字段如下：
            - title: 字符串，5~20 字的简短标题
            - content: 字符串，记忆正文，自包含、可独立理解（脱离本次对话也能看懂）
            - category: 字符串，枚举之一：USER_PREFERENCE, PROJECT_INFO, DEVELOPMENT_SPEC, EXPERIENCE, PITFALL, DECISION, OTHER
            - scope: 字符串，枚举之一：GLOBAL(跨项目的用户级), PROJECT(项目内), SESSION(仅本会话)
            - keywords: 字符串数组，2~6 个便于检索的关键词

            要求：
            1. 每条记忆聚焦单一主题，原子化；
            2. 若对话中没有值得长期保留的内容，输出 []；
            3. content 用陈述句，不要包含“我/你/本次对话”等指代；
            4. 对话记录可能只是完整对话的片段（开头带有省略标记）：只从可见内容提炼，不要臆测缺失部分；
            5. 用户的纠正/改口通常出现在对话后段，以其为准，被省略标记隔开的早期表述视为已过时。
            """;

    private final Summarizer summarizer;
    private final int maxInputChars;

    public MemoryExtractor(Summarizer summarizer) {
        this.summarizer = summarizer;
        this.maxInputChars = ConfigUtil.getInt(MAX_INPUT_KEY, DEFAULT_MAX_INPUT);
    }

    /**
     * 从对话记录中抽取记忆条目。
     *
     * @param sessionId  来源会话 id，写入每条记忆的 sourceSessionId
     * @param transcript 本轮模型上下文（会自动跳过 system / reasoning，并按预算截断）
     * @return 抽取到的记忆（已分配 id 与时间戳）；无内容或失败时返回空列表
     */
    public List<Memory> extract(String sessionId, List<MessageItem> transcript) {
        if (summarizer == null || transcript == null || transcript.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            String convo = renderTranscript(transcript);
            if (convo.isBlank()) {
                return Collections.emptyList();
            }
            List<MessageItem> request = new ArrayList<>();
            request.add(new SystemMessageItem(SYSTEM_PROMPT));
            request.add(new UserMessageItem("对话记录：\n" + convo));

            String output = summarizer.summarize(request);
            if (output == null || output.isBlank()) {
                return Collections.emptyList();
            }
            List<Memory> memories = parseMemories(output, sessionId);
            log.info("MemoryExtractor: extracted {} memories from session {}", memories.size(), sessionId);
            return memories;
        } catch (Exception e) {
            log.error("MemoryExtractor: extract failed, sessionId={}", sessionId, e);
            return Collections.emptyList();
        }
    }

    /**
     * 把对话渲染成纯文本（role: content），跳过 system 与 reasoning。
     * <p>超预算时<b>从尾部（最新消息）向前保留</b>：用户偏好、纠正、最终决策几乎总出现在对话后段，
     * 旧的「从头拼接、满了截断」策略会系统性丢掉最新关键信息——比如先说“回答详细点”
     * 后改口“先给结论再解释”，截断后只剩前者就会沉淀过时偏好。被省略的前文以显式标记
     * 告知抽取器（配合上面指令第 4/5 条），而不是静默丢弃。
     */
    private String renderTranscript(List<MessageItem> transcript) {
        List<String> lines = new ArrayList<>();
        for (MessageItem item : transcript) {
            if (item instanceof SystemMessageItem || item instanceof ReasoningMessageItem) {
                continue;
            }
            String text = item.transfer2prompt();
            if (text == null || text.isBlank()) {
                continue;
            }
            lines.add(item.role() + ": " + text);
        }
        if (lines.isEmpty()) {
            return "";
        }
        int total = 0;
        for (String line : lines) {
            total += line.length() + 1;
        }
        if (total <= maxInputChars) {
            return String.join("\n", lines);
        }
        // 省略标记本身也占预算；先定条数无法预知确切文本，按最大长度预留
        int budget = Math.max(0, maxInputChars - 64);
        int acc = 0;
        int start = lines.size();
        for (int i = lines.size() - 1; i >= 0; i--) {
            int len = lines.get(i).length() + (i == lines.size() - 1 ? 0 : 1);
            if (acc + len > budget) {
                break;
            }
            acc += len;
            start = i;
        }
        StringBuilder sb = new StringBuilder();
        if (start == lines.size()) {
            // 连最新一条都放不下：只保留其尾部（信息最密集处），确保抽取器至少见到最近内容
            String last = lines.get(lines.size() - 1);
            sb.append("...[前 ").append(lines.size() - 1).append(" 条消息因超出预算被省略，仅保留最新一条的尾部]...\n");
            sb.append(last, Math.max(0, last.length() - budget), last.length());
            return sb.toString();
        }
        sb.append("...[前 ").append(start).append(" 条消息因超出预算被省略，以下保留最近的对话]...\n");
        sb.append(String.join("\n", lines.subList(start, lines.size())));
        return sb.toString();
    }

    /**
     * 解析 LLM 输出为记忆列表：先截取 JSON 数组子串（容忍围栏/多余文本），再逐项防御式映射
     */
    private List<Memory> parseMemories(String output, String sessionId) {
        String json = extractJsonArray(output);
        if (json == null) {
            log.warn("MemoryExtractor: no JSON array found in model output");
            return Collections.emptyList();
        }
        List<Map<String, Object>> drafts = JsonUtil.parse(json, new TypeReference<List<Map<String, Object>>>() {
        });
        if (drafts == null || drafts.isEmpty()) {
            return Collections.emptyList();
        }
        List<Memory> memories = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Map<String, Object> draft : drafts) {
            String content = asString(draft.get("content"));
            if (content == null || content.isBlank()) {
                continue;
            }
            Memory m = new Memory();
            m.setId(CommonUtils.generateId());
            m.setTitle(asString(draft.get("title")));
            m.setContent(content.trim());
            m.setCategory(MemoryCategory.from(asString(draft.get("category"))));
            m.setScope(MemoryScope.from(asString(draft.get("scope"))));
            m.setKeywords(asKeywords(draft.get("keywords")));
            m.setSourceSessionId(sessionId);
            m.setCreatedAt(now);
            m.setUpdatedAt(now);
            memories.add(m);
        }
        return memories;
    }

    /**
     * 从模型输出中截取第一个完整的 JSON 数组（去掉 ```json 围栏或前后解释文字）
     */
    private String extractJsonArray(String output) {
        String t = output.trim();
        int start = t.indexOf('[');
        int end = t.lastIndexOf(']');
        if (start >= 0 && end > start) {
            return t.substring(start, end + 1);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<String> asKeywords(Object raw) {
        List<String> keywords = new ArrayList<>();
        if (raw instanceof Collection<?> coll) {
            for (Object o : coll) {
                String s = asString(o);
                if (s != null && !s.isBlank()) {
                    keywords.add(s.trim());
                }
            }
        } else if (raw instanceof String s && !s.isBlank()) {
            for (String part : s.split(",")) {
                if (!part.isBlank()) {
                    keywords.add(part.trim());
                }
            }
        }
        return keywords;
    }

    private String asString(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
