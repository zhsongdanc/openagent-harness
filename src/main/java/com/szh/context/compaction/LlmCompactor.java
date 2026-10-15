package com.szh.context.compaction;

import com.szh.context.dto.AssistantMessageItem;
import com.szh.context.dto.MessageItem;
import com.szh.context.dto.SystemMessageItem;
import com.szh.context.dto.ToolMessageItem;
import com.szh.tool.store.ToolResultStore;
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
            7. 若历史中出现 result_id=xxx 形式的工具结果把手，必须原样保留这些 result_id，不得改写或省略
            
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
     * @return 压缩结果：成功时为 {@code summarized}（携带摘要正文与保留尾部条数）；
     *         各 bail 分支返回 {@code l0Only(原列表)} 表示未产生摘要，由上层回退到 L0 结果
     */
    public CompactionResult compact(List<MessageItem> messages) {
        if (messages == null || messages.size() <= KEEP_RECENT + 1) {
            return CompactionResult.l0Only(messages);
        }

        // 分离：system prompt + 待压缩历史 + 保留的近期消息
        MessageItem systemPrompt = messages.get(0);
        int keepStart = Math.max(1, messages.size() - KEEP_RECENT);
        // 边界对齐：KEEP_RECENT 是按条数硬切的，可能正好落在某轮工具调用内部——
        // modelContext 里一轮是连续段（若干 function_call 紧跟其 function_call_output），
        // 若把 function_call 折进摘要、却把对应的 function_call_output 留在尾部，
        // convertInput 会发出没有配对 call 的孤儿 output，DeepSeek Responses API 直接报 400
        // （"No tool call found for tool output with call_id ..."）。这里把 keepStart 回退到整轮开头，
        // 保证尾部要么完整含一整轮、要么完全不含，绝不切在轮中间。
        keepStart = alignToToolRoundStart(messages, keepStart);
        List<MessageItem> toCompress = messages.subList(1, keepStart);
        List<MessageItem> recentMessages = messages.subList(keepStart, messages.size());

        if (toCompress.isEmpty()) {
            return CompactionResult.l0Only(messages);
        }

        // 构建待压缩历史的文本；同时收集其中仍可回读的落盘把手（P0-3）
        StringBuilder historyText = new StringBuilder();
        List<String> handles = new ArrayList<>();
        for (MessageItem msg : toCompress) {
            historyText.append("[").append(msg.role()).append("] ")
                    .append(msg.transfer2prompt())
                    .append("\n");
            if (msg instanceof ToolMessageItem toolMsg) {
                String rid = ToolResultStore.extractSpilledResultId(toolMsg.getExecResult());
                if (rid != null) {
                    handles.add("- " + toolMsg.getToolCode() + " → read_tool_result(result_id=" + rid + ")");
                }
            }
        }

        // 调用 LLM 生成摘要
        String summary = callLlmForSummary(historyText.toString());
        if (summary == null || summary.isEmpty()) {
            log.warn("LlmCompactor: LLM summary failed, falling back to L0 result");
            return CompactionResult.l0Only(messages);
        }

        // P0-3：把手清单由代码确定性拼接、不经 LLM 改写，避免 result_id 被摘要吃掉导致落盘文件成孤儿
        StringBuilder summaryText = new StringBuilder("[历史摘要]\n").append(summary);
        if (!handles.isEmpty()) {
            summaryText.append("\n\n[可回读的历史工具结果把手]\n")
                    .append("以下结果的完整正文仍保存在文件中，需要时用 read_tool_result 按 result_id 分页回读：\n")
                    .append(String.join("\n", handles));
        }

        // 构建压缩后的上下文：system prompt + 摘要 + 近期消息
        List<MessageItem> result = new ArrayList<>();
        result.add(systemPrompt);
        result.add(new SystemMessageItem(summaryText.toString()));
        result.addAll(recentMessages);

        int originalTokens = StepCompactor.estimateTotalTokens(messages);
        int compressedTokens = StepCompactor.estimateTotalTokens(result);
        log.info("LlmCompactor L1: messages {} -> {}, tokens ~{} -> ~{}",
                messages.size(), result.size(), originalTokens, compressedTokens);

        // 摘要正文与保留尾部条数一并回传，供上层落 ContextCompactedEvent 检查点，使 resume 能逐字还原压缩态
        return CompactionResult.summarized(result, summaryText.toString(), recentMessages.size());
    }

    /**
     * 把压缩边界回退对齐到完整工具调用轮的开头。
     * <p>
     * 工具轮在扁平的 modelContext 里表现为连续段：先若干 {@code AssistantMessageItem(callTool)}
     * （一轮多调用时逐个相邻落库），紧跟其若干 {@code ToolMessageItem}。若 {@code keepStart}
     * 指向的正是这一段内部（本身是工具项），则向前回退到该段起点，使整轮并入尾部；
     * 若 {@code keepStart} 落在非工具项（user/system/reasoning 或纯文本 assistant）上，
     * 说明已是安全边界，原样返回。
     */
    private int alignToToolRoundStart(List<MessageItem> messages, int keepStart) {
        if (keepStart <= 1 || !isToolRoundItem(messages.get(keepStart))) {
            return keepStart;
        }
        int i = keepStart;
        // 只要前一条仍属于同一轮（call 或 output），继续向前吃掉整轮
        while (i > 1 && isToolRoundItem(messages.get(i - 1))) {
            i--;
        }
        return i;
    }

    /**
     * 是否属于工具调用轮的组成项：function_call（callTool 的 assistant）或 function_call_output（工具结果）。
     */
    private boolean isToolRoundItem(MessageItem msg) {
        return msg instanceof ToolMessageItem
                || (msg instanceof AssistantMessageItem assistant && assistant.isCallTool());
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
