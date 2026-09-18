package com.szh.context.compaction;

import com.szh.context.dto.AssistantMessageItem;
import com.szh.context.dto.MessageItem;
import com.szh.context.dto.ReasoningMessageItem;
import com.szh.context.dto.ToolMessageItem;
import com.szh.utils.ConfigUtil;
import com.szh.utils.TokenEstimator;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * L0 规则压缩（StepCompact）：不调用 LLM，通过规则快速压缩上下文。
 * <p>
 * 策略：
 * 1. 截断工具结果：超过阈值的 ToolMessageItem 截断，保留头尾 + 提示
 * 2. 截断思维链：截断 ReasoningMessageItem（DeepSeek Responses API 要求 reasoning_text 必须回传，不能移除）
 * 3. 压缩 assistant 消息中过长的工具调用参数
 * 4. 保留最近 N 条消息不压缩，只处理更早的消息
 *
 * @author demussong
 * @date 2026/9/17
 */
@Slf4j
public class StepCompactor {

    /**
     * 工具结果最大字符数，超过则截断
     */
    private static final int TOOL_RESULT_MAX_CHARS = ConfigUtil.getInt("compaction.tool.result.max.chars", 2000);

    /**
     * 思维链最大保留字符数，超过则截断（DeepSeek Responses API 要求 reasoning_text 必须回传，不能移除）
     */
    private static final int REASONING_MAX_CHARS = ConfigUtil.getInt("compaction.reasoning.max.chars", 500);

    /**
     * 保留最近 N 条消息不压缩
     */
    private static final int KEEP_RECENT = ConfigUtil.getInt("compaction.keep.recent", 6);

    /**
     * 对消息列表执行 L0 规则压缩。
     *
     * @param messages 当前上下文消息列表（包含 system prompt）
     * @return 压缩后的消息列表（新列表，不修改原列表）
     */
    public List<MessageItem> compact(List<MessageItem> messages) {
        if (messages == null || messages.size() <= KEEP_RECENT + 1) {
            return messages;
        }

        List<MessageItem> result = new ArrayList<>();
        // 保留 system prompt（第一条）
        result.add(messages.get(0));

        int compressEnd = messages.size() - KEEP_RECENT;

        for (int i = 1; i < messages.size(); i++) {
            MessageItem msg = messages.get(i);

            if (i >= compressEnd) {
                // 最近 N 条，保持原样
                result.add(msg);
                continue;
            }

            // 对较早的消息执行压缩规则
            MessageItem compressed = compressMessage(msg);
            if (compressed != null) {
                result.add(compressed);
            }
        }

        int originalTokens = estimateTotalTokens(messages);
        int compressedTokens = estimateTotalTokens(result);
        log.info("StepCompactor L0: messages {} -> {}, tokens ~{} -> ~{}",
                messages.size(), result.size(), originalTokens, compressedTokens);

        return result;
    }

    /**
     * 压缩单条消息，返回 null 表示该消息应被移除
     */
    private MessageItem compressMessage(MessageItem msg) {
        // 截断思维链（DeepSeek Responses API 要求 reasoning_text 必须回传，不能移除）
        if (msg instanceof ReasoningMessageItem reasoningMsg) {
            String content = reasoningMsg.getContent();
            if (content != null && content.length() > REASONING_MAX_CHARS) {
                String truncated = content.substring(0, REASONING_MAX_CHARS)
                        + "\n... [reasoning truncated, " + (content.length() - REASONING_MAX_CHARS) + " chars omitted]";
                return new ReasoningMessageItem(truncated, reasoningMsg.getRawContentJson());
            }
            return msg;
        }

        // 截断工具结果
        if (msg instanceof ToolMessageItem toolMsg) {
            String result = toolMsg.getExecResult();
            if (result != null && result.length() > TOOL_RESULT_MAX_CHARS) {
                int headLen = TOOL_RESULT_MAX_CHARS * 2 / 3;
                int tailLen = TOOL_RESULT_MAX_CHARS / 3;
                String truncated = result.substring(0, headLen)
                        + "\n... [truncated, " + (result.length() - headLen - tailLen) + " chars omitted] ...\n"
                        + result.substring(result.length() - tailLen);
                return new ToolMessageItem(toolMsg.getCallId(), toolMsg.getToolCode(), truncated);
            }
            return msg;
        }

        // 压缩 assistant 消息中的工具调用参数（如果参数过长）
        if (msg instanceof AssistantMessageItem assistantMsg && assistantMsg.isCallTool()) {
            String args = assistantMsg.getToolArgs();
            if (args != null && args.length() > TOOL_RESULT_MAX_CHARS) {
                String truncated = args.substring(0, TOOL_RESULT_MAX_CHARS)
                        + "... [truncated]";
                AssistantMessageItem compressed = new AssistantMessageItem(
                        assistantMsg.getToolCallId(), assistantMsg.getToolCode(), truncated);
                return compressed;
            }
            return msg;
        }

        // 其他消息保持原样（user messages, system messages 等）
        return msg;
    }

    /**
     * 估算消息列表的总 token 数
     */
    public static int estimateTotalTokens(List<MessageItem> messages) {
        int total = 0;
        for (MessageItem msg : messages) {
            String prompt = msg.transfer2prompt();
            total += TokenEstimator.estimateTokens(prompt);
            // 加上 role 标记等开销
            total += 4;
        }
        return total;
    }
}
