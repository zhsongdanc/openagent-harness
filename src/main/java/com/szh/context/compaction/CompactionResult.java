package com.szh.context.compaction;

import com.szh.context.dto.MessageItem;

import java.util.List;

/**
 * 一次压缩调用的结构化结果。
 * <p>
 * 相比直接返回 {@code List<MessageItem>}，额外携带「是否真的变了」与「是否产生了 L1 摘要」两个信号：
 * <ul>
 *   <li>{@link #isChanged()}：运行时据此决定是否 {@code replaceModelContext}——比旧的「比较消息条数」更准，
 *       L0 只截断正文不改条数时也能正确替换。</li>
 *   <li>{@link #isSummarized()}：仅 L1（LLM 摘要）触发时为 true，此时 {@link #getSummary()}/{@link #getKeepRecentCount()}
 *       有值，运行时据此落 {@code ContextCompactedEvent} 检查点，让 resume 无需再调 LLM 即可重建压缩态。</li>
 * </ul>
 * L0-only 压缩（{@link #l0Only}）不落检查点：它是确定性零成本的，恢复后重跑即得相同结果。
 *
 * @author demussong
 * @date 2026/10/14
 */
public class CompactionResult {

    private final List<MessageItem> messages;
    private final boolean changed;
    private final String summary;
    private final int keepRecentCount;

    private CompactionResult(List<MessageItem> messages, boolean changed, String summary, int keepRecentCount) {
        this.messages = messages;
        this.changed = changed;
        this.summary = summary;
        this.keepRecentCount = keepRecentCount;
    }

    /**
     * 未触发压缩：原样返回入参上下文。
     */
    public static CompactionResult unchanged(List<MessageItem> messages) {
        return new CompactionResult(messages, false, null, 0);
    }

    /**
     * 仅 L0 规则压缩（或 L1 未产生有效摘要而回退）：上下文变了，但没有可固化的摘要。
     */
    public static CompactionResult l0Only(List<MessageItem> messages) {
        return new CompactionResult(messages, true, null, 0);
    }

    /**
     * L1 LLM 摘要压缩成功：携带摘要正文与其后保留的尾部消息数，供落检查点事件。
     */
    public static CompactionResult summarized(List<MessageItem> messages, String summary, int keepRecentCount) {
        return new CompactionResult(messages, true, summary, keepRecentCount);
    }

    public List<MessageItem> getMessages() {
        return messages;
    }

    public boolean isChanged() {
        return changed;
    }

    public boolean isSummarized() {
        return summary != null;
    }

    public String getSummary() {
        return summary;
    }

    public int getKeepRecentCount() {
        return keepRecentCount;
    }
}
