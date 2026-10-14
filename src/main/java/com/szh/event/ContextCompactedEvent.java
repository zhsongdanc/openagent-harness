package com.szh.event;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 上下文压缩检查点事件：L1（LLM 摘要）压缩发生时落一条，把摘要结果固化进事件流。
 * <p>
 * 与 {@link TodoUpdatedEvent}/{@link VerificationEvent} 一样刻意<b>不</b>继承 {@link MessageEvent}——
 * 它不是一条要喂给模型的消息，而是一个「重塑上下文」的控制型检查点。但它又与那些 surface 事件不同：
 * {@code AgentState.resume()}/{@code deriveMessages()} 会<b>特判消费</b>它，把该事件之前累积的历史
 * 折叠为 {@code [system, summary, 尾部 keepRecentCount 条]}，从而直接重建到压缩后状态，
 * 无需再次调用 LLM 重压（L1 摘要既花成本又非确定性，必须固化才能逐字还原）。
 * <p>
 * L0（{@code StepCompactor}）规则压缩是确定性零成本的，恢复后重跑即得相同结果，故不落检查点。
 *
 * @author demussong
 * @date 2026/10/14
 */
public class ContextCompactedEvent extends Event {

    /**
     * 摘要 {@code SystemMessageItem} 的完整正文（含「可回读的历史工具结果把手」清单），
     * 以运行期实际注入上下文的文本为准存储，保证 resume 逐字还原。
     */
    private final String summary;

    /**
     * 摘要之后保留的尾部消息数：resume 折叠时从重建上下文取末尾这么多条原文，
     * 与运行期 {@code LlmCompactor} 保留的 recentMessages 数量一致。
     */
    private final int keepRecentCount;

    /**
     * 压缩前消息数（仅可观测，供回放展示）
     */
    private final int beforeMessages;

    /**
     * 压缩后消息数（仅可观测，供回放展示）
     */
    private final int afterMessages;

    public ContextCompactedEvent(String sessionId, String runId, String turnId, int round,
                                 String summary, int keepRecentCount, int beforeMessages, int afterMessages) {
        super();
        this.sessionId = sessionId;
        this.runId = runId;
        this.turnId = turnId;
        this.round = round;
        this.summary = summary;
        this.keepRecentCount = keepRecentCount;
        this.beforeMessages = beforeMessages;
        this.afterMessages = afterMessages;
    }

    public String getSummary() {
        return summary;
    }

    public int getKeepRecentCount() {
        return keepRecentCount;
    }

    public int getBeforeMessages() {
        return beforeMessages;
    }

    public int getAfterMessages() {
        return afterMessages;
    }

    @Override
    public EventEnum getType() {
        return EventEnum.CONTEXT_COMPACTED;
    }

    @Override
    public Object payloadData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("summary", summary);
        data.put("keepRecentCount", keepRecentCount);
        data.put("beforeMessages", beforeMessages);
        data.put("afterMessages", afterMessages);
        return data;
    }
}
