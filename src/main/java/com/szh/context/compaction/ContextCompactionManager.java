package com.szh.context.compaction;

import com.szh.context.dto.MessageItem;
import com.szh.trace.TokenTracker;
import com.szh.utils.ConfigUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 上下文压缩调度器：单一阈值触发，渐进式升级。
 * <p>
 * 触发逻辑（参考 Claude Code / DeepSeek Harness 的主流设计）：
 * 1. 每轮检查 currentTokens / contextWindow 是否超过阈值
 * 2. 超过 → 先执行 L0 规则压缩（低成本，不调 LLM）
 * 3. L0 后仍超阈值 → 升级为 L1 LLM 摘要压缩
 * <p>
 * 工具结果已由 ToolResultStore 外存到文件，上下文中仅保留短引用，
 * 因此 L0 主要针对思维链移除和残留长文本截断，L1 是真正有效的压缩手段。
 *
 * @author demussong
 * @date 2026/9/17
 */
@Slf4j
public class ContextCompactionManager {

    /**
     * 模型上下文窗口大小
     */
    private final int contextWindow;

    /**
     * 压缩触发比例（如 0.7 = 70%），单一阈值
     */
    private final double threshold;

    private final StepCompactor stepCompactor;
    private final LlmCompactor llmCompactor;
    private final TokenTracker tokenTracker;

    public ContextCompactionManager(Summarizer summarizer, TokenTracker tokenTracker) {
        this.contextWindow = ConfigUtil.getInt("model.context.window", 64000);
        this.threshold = Double.parseDouble(ConfigUtil.get("compaction.threshold", "0.7"));
        this.stepCompactor = new StepCompactor();
        this.llmCompactor = summarizer != null ? new LlmCompactor(summarizer) : null;
        this.tokenTracker = tokenTracker;
        if (summarizer == null) {
            log.warn("[Compaction] Summarizer 为 null，L1 LLM 摘要压缩不可用，仅启用 L0 规则压缩");
        }
    }

    /**
     * 检查是否需要压缩，如需要则渐进式执行：L0 规则压缩 → 如仍超阈值则 L1 LLM 摘要。
     *
     * @param messages 当前上下文消息列表
     * @return 压缩后的消息列表（可能是原列表）
     */
    public List<MessageItem> maybeCompact(List<MessageItem> messages) {
        int currentTokens = tokenTracker.getCurrentContextTokens();

        // 如果当前 token 数未知（首次调用），用估算值
        if (currentTokens == 0) {
            currentTokens = StepCompactor.estimateTotalTokens(messages);
            log.info("[Compaction] 首次调用，服务端 token 数未知，使用估算值: {}", currentTokens);
        }

        double ratio = (double) currentTokens / contextWindow;
        String ratioStr = String.format("%.2f", ratio);
        log.info("[Compaction] 检查: tokens={}, ratio={} (阈值={}), 消息数={}",
                currentTokens, ratioStr, threshold, messages.size());

        // 未超过阈值，不压缩
        if (ratio < threshold) {
            return messages;
        }

        log.info("[Compaction] 触发压缩: ratio={} >= {}, 消息数={}",
                ratioStr, threshold, messages.size());

        // Step 1: L0 规则压缩（低成本，不调 LLM）；细节见 StepCompactor 内部日志
        List<MessageItem> compacted = stepCompactor.compact(messages);
        int l0Tokens = StepCompactor.estimateTotalTokens(compacted);
        tokenTracker.recalibrateContextTokens(l0Tokens);
        String l0RatioStr = String.format("%.2f", (double) l0Tokens / contextWindow);

        // Step 2: L0 后仍超阈值 → 升级为 L1 LLM 摘要；细节见 LlmCompactor 内部日志
        if ((double) l0Tokens / contextWindow >= threshold) {
            if (llmCompactor == null) {
                log.warn("[Compaction] L0 后 ratio={} 仍 >= 阈值 {}，但 Summarizer 为 null，无法执行 L1 压缩",
                        l0RatioStr, threshold);
            } else {
                int l0MsgCount = compacted.size();
                compacted = llmCompactor.compact(compacted);
                int l1Tokens = StepCompactor.estimateTotalTokens(compacted);
                tokenTracker.recalibrateContextTokens(l1Tokens);
                log.info("[Compaction] L0+L1 完成: ratio {} -> {} -> {}, 消息数 {} -> {} -> {}",
                        ratioStr, l0RatioStr, String.format("%.2f", (double) l1Tokens / contextWindow),
                        messages.size(), l0MsgCount, compacted.size());
            }
        } else {
            log.info("[Compaction] L0 完成: ratio {} -> {}, 消息数 {} -> {}",
                    ratioStr, l0RatioStr, messages.size(), compacted.size());
        }

        return compacted;
    }
}
