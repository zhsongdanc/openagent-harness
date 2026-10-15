package com.szh.event;

import com.szh.model.dto.TokenUsage;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 每轮模型调用的 token 用量事件：把运行期 {@code TokenTracker} 里只会随日志打印、无法事后查询的用量
 * 固化进事件流，供本地 Trace 查看器（{@code com.szh.web}）与 {@code TraceReplay} 按轮展示。
 * <p>
 * 与 {@link VerificationEvent}/{@link TodoUpdatedEvent} 同理，是面向用户/回放的 surface 事件——刻意<b>不</b>继承
 * {@link MessageEvent}、不进 modelContext（用量是运行期指标，与模型语义无关；塞进上下文既冗余，又会破坏
 * Responses API 的 function_call 分组约束）。故 {@code AgentState.resume()} 天然忽略它，只由 REPL / 查看器 / 回放消费。
 * <p>
 * 除服务端返回的四项用量外，额外带上「本轮上下文条数 + 估算 token + 模型调用耗时」——上下文正文由
 * {@code ContextSnapshotStore} 另存侧车文件（独立于事件日志），这里只留规模指标，让查看器不用读全量快照
 * 也能一眼看出「这轮上下文体量」与「真实 promptTokens」的差异。
 *
 * @author demussong
 * @date 2026/10/15
 */
public class TokenUsageEvent extends Event {

    private final int promptTokens;

    private final int completionTokens;

    private final int totalTokens;

    /**
     * 服务端缓存命中的 token 数（部分 provider 不返回，记 0）
     */
    private final int cachedTokens;

    /**
     * 本轮实际发给模型的消息条数（含 system），便于查看器展示上下文体量
     */
    private final int contextMessages;

    /**
     * 本轮上下文的本地估算 token 数（{@code StepCompactor.estimateTotalTokens}），与真实 promptTokens 对照用
     */
    private final int contextEstimatedTokens;

    /**
     * 本轮 model.call 耗时（毫秒）——含重试与流式等待，是"模型到底花多久"的实测值
     */
    private final long elapsedMs;

    /**
     * 运行时构造：直接消费 {@link TokenUsage}；usage 可能为 null（如未返回用量的 provider / 测试用 FakeModel），
     * 此时四项用量一律记 0，但上下文规模与耗时照常记录。
     */
    public TokenUsageEvent(String sessionId, String runId, String turnId, int round,
                           TokenUsage usage, int contextMessages, int contextEstimatedTokens, long elapsedMs) {
        this(sessionId, runId, turnId, round,
                usage == null ? 0 : usage.getPromptTokens(),
                usage == null ? 0 : usage.getCompletionTokens(),
                usage == null ? 0 : usage.getTotalTokens(),
                usage == null ? 0 : usage.getCachedTokens(),
                contextMessages, contextEstimatedTokens, elapsedMs);
    }

    /**
     * 全字段构造：反序列化（{@code EventJsonCodec}）与序列化往返使用，避免把扁平 payload 再拼回 TokenUsage
     */
    public TokenUsageEvent(String sessionId, String runId, String turnId, int round,
                           int promptTokens, int completionTokens, int totalTokens, int cachedTokens,
                           int contextMessages, int contextEstimatedTokens, long elapsedMs) {
        this.sessionId = sessionId;
        this.runId = runId;
        this.turnId = turnId;
        this.round = round;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = totalTokens;
        this.cachedTokens = cachedTokens;
        this.contextMessages = contextMessages;
        this.contextEstimatedTokens = contextEstimatedTokens;
        this.elapsedMs = elapsedMs;
    }

    public int getPromptTokens() {
        return promptTokens;
    }

    public int getCompletionTokens() {
        return completionTokens;
    }

    public int getTotalTokens() {
        return totalTokens;
    }

    public int getCachedTokens() {
        return cachedTokens;
    }

    public int getContextMessages() {
        return contextMessages;
    }

    public int getContextEstimatedTokens() {
        return contextEstimatedTokens;
    }

    public long getElapsedMs() {
        return elapsedMs;
    }

    @Override
    public EventEnum getType() {
        return EventEnum.TOKEN_USAGE;
    }

    @Override
    public Object payloadData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("promptTokens", promptTokens);
        data.put("completionTokens", completionTokens);
        data.put("totalTokens", totalTokens);
        data.put("cachedTokens", cachedTokens);
        data.put("contextMessages", contextMessages);
        data.put("contextEstimatedTokens", contextEstimatedTokens);
        data.put("elapsedMs", elapsedMs);
        return data;
    }

    @Override
    public String toString() {
        return "TokenUsageEvent[prompt=" + promptTokens + ", completion=" + completionTokens
                + ", total=" + totalTokens + ", cached=" + cachedTokens
                + ", ctx=" + contextMessages + " 条/" + contextEstimatedTokens + "t, elapsed=" + elapsedMs + "ms]";
    }
}
