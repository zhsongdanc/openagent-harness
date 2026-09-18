package com.szh.context.compaction;

import com.szh.context.dto.AssistantMessageItem;
import com.szh.context.dto.MessageItem;
import com.szh.context.dto.SystemMessageItem;
import com.szh.utils.ConfigUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * L1 LLM 摘要压缩：当 L0 压缩后仍超过阈值时，调用 LLM 将较早历史生成结构化摘要，
 * 用摘要 + 保留的近期消息替换原始历史。
 * <p>
 * 摘要格式参考 Codex 的 handoff summary：
 * - 当前进度和关键决策
 * - 重要约束和用户偏好
 * - 剩余 TODO
 * - 继续工作所需的关键数据
 *
 * @author demussong
 * @date 2026/9/17
 */
@Slf4j
public class LlmCompactor {

    private static final int KEEP_RECENT = ConfigUtil.getInt("compaction.keep.recent", 6);

    private static final String COMPACT_PROMPT = """
            你是一个上下文压缩助手。请将以下对话历史压缩成一份简洁的交接摘要，供另一个 LLM 接续工作。
            要求：
            1. 保留当前任务目标和进度
            2. 保留关键决策和约束条件
            3. 保留剩余 TODO 和待解决问题
            4. 保留关键数据（文件路径、报错信息、重要 ID 等）
            5. 移除冗余的工具调用细节，只保留结论
            6. 保持摘要简洁，不超过原文的 1/3 长度
            
            请直接输出摘要内容，不要添加额外说明。
            
            以下是需要压缩的历史对话：
            """;

    private final Summarizer summarizer;

    public LlmCompactor(Summarizer summarizer) {
        this.summarizer = summarizer;
    }

    /**
     * 执行 L1 LLM 压缩。
     *
     * @param messages 当前上下文消息列表（已经过 L0 压缩）
     * @return 压缩后的消息列表：[systemPrompt, summaryMessage, ...recentMessages]
     */
    public List<MessageItem> compact(List<MessageItem> messages) {
        if (messages == null || messages.size() <= KEEP_RECENT + 1) {
            return messages;
        }

        // 分离：system prompt + 待压缩历史 + 保留的近期消息
        MessageItem systemPrompt = messages.get(0);
        int keepStart = Math.max(1, messages.size() - KEEP_RECENT);
        List<MessageItem> toCompress = messages.subList(1, keepStart);
        List<MessageItem> recentMessages = messages.subList(keepStart, messages.size());

        if (toCompress.isEmpty()) {
            return messages;
        }

        // 构建待压缩历史的文本
        StringBuilder historyText = new StringBuilder();
        for (MessageItem msg : toCompress) {
            historyText.append("[").append(msg.role()).append("] ")
                    .append(msg.transfer2prompt())
                    .append("\n");
        }

        // 调用 LLM 生成摘要
        String summary = callLlmForSummary(historyText.toString());
        if (summary == null || summary.isEmpty()) {
            log.warn("LlmCompactor: LLM summary failed, falling back to L0 result");
            return messages;
        }

        // 构建压缩后的上下文：system prompt + 摘要 + 近期消息
        List<MessageItem> result = new ArrayList<>();
        result.add(systemPrompt);
        result.add(new SystemMessageItem("[历史摘要]\n" + summary));
        result.addAll(recentMessages);

        int originalTokens = StepCompactor.estimateTotalTokens(messages);
        int compressedTokens = StepCompactor.estimateTotalTokens(result);
        log.info("LlmCompactor L1: messages {} -> {}, tokens ~{} -> ~{}",
                messages.size(), result.size(), originalTokens, compressedTokens);

        return result;
    }

    /**
     * 调用 LLM 生成摘要。
     * <p>
     * 这里只负责构建摘要请求消息（压缩指令 + 待压缩历史），具体的模型协议调用
     * 由 {@link Summarizer} 适配器完成；调用失败时返回 null，由 {@link #compact} 回退到 L0 结果。
     */
    private String callLlmForSummary(String historyText) {
        List<MessageItem> summaryMessages = new ArrayList<>();
        summaryMessages.add(new SystemMessageItem(COMPACT_PROMPT));
        summaryMessages.add(new AssistantMessageItem(historyText));
        return summarizer.summarize(summaryMessages);
    }
}
