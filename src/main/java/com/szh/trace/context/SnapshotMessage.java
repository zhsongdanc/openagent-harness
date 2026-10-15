package com.szh.trace.context;

import com.szh.context.dto.AssistantMessageItem;
import com.szh.context.dto.MessageItem;
import com.szh.context.dto.ReasoningMessageItem;
import com.szh.context.dto.SystemMessageItem;
import com.szh.context.dto.ToolMessageItem;
import com.szh.context.dto.UserMessageItem;
import com.szh.model.dto.ToolCall;
import com.szh.utils.TokenEstimator;

import java.util.List;

/**
 * 上下文快照里的单条消息（中性 DTO）——把 {@link MessageItem} 展平成「角色 + 正文 + 工具调用」的可视化结构。
 * <p>
 * 字段全部冗余可空：不同 kind 只填各自的字段（system/user 只有 content；assistant 可能是 content 或 toolCalls；
 * tool 是 callId+toolCode+execResult）。这样前端无需了解 Java DTO 继承体系即可按 kind 渲染。
 *
 * @param role            消息角色（system/user/assistant/reasoning/tool），取自 {@code MessageItem.role()}
 * @param kind            消息种类（SYSTEM/USER/REASONING/ASSISTANT/TOOL），用于前端着色与图标
 * @param content         正文（tool 消息为执行结果存根）
 * @param toolCallId      单工具调用的 id（assistant 场景，标量快照）
 * @param toolCode        工具名（assistant 场景，标量快照）
 * @param toolArgs        工具参数 JSON（assistant 场景，标量快照）
 * @param toolCalls       一轮内的全部工具调用（并行多调用；单调用时为单元素列表）
 * @param callId          工具结果对应的调用 id（tool 场景）
 * @param toolResultCode  产出该结果的工具名（tool 场景）
 * @param raw             原始附加数据（如 reasoning 的 rawContentJson），无则 null
 * @param estimatedTokens 该条消息的本地估算 token（口径与 {@code StepCompactor.estimateTotalTokens} 一致）
 * @author demussong
 * @date 2026/10/15
 */
public record SnapshotMessage(
        String role,
        String kind,
        String content,
        String toolCallId,
        String toolCode,
        String toolArgs,
        List<SnapshotToolCall> toolCalls,
        String callId,
        String toolResultCode,
        String raw,
        int estimatedTokens
) {

    public static final String KIND_SYSTEM = "SYSTEM";
    public static final String KIND_USER = "USER";
    public static final String KIND_REASONING = "REASONING";
    public static final String KIND_ASSISTANT = "ASSISTANT";
    public static final String KIND_TOOL = "TOOL";

    /**
     * 把运行期的消息项转成快照 DTO；未知实现类退化为「原样文本 + role」，保证任何新消息类型都不会漏采。
     * <p>
     * token 估算口径与 {@code StepCompactor.estimateTotalTokens} 保持一致（+4 为 role 标记等开销），
     * 便于与 {@code TokenUsageEvent.contextEstimatedTokens} 对上。
     */
    public static SnapshotMessage from(MessageItem item) {
        if (item == null) {
            return new SnapshotMessage(null, "UNKNOWN", null, null, null, null, null, null, null, null, 0);
        }
        int tokens = TokenEstimator.estimateTokens(item.transfer2prompt()) + 4;
        String role = item.role();
        if (item instanceof SystemMessageItem system) {
            return new SnapshotMessage(role, KIND_SYSTEM, system.getContent(),
                    null, null, null, null, null, null, null, tokens);
        }
        if (item instanceof com.szh.context.dto.UserMessageItem user) {
            return new SnapshotMessage(role, KIND_USER, user.getContent(),
                    null, null, null, null, null, null, null, tokens);
        }
        if (item instanceof ReasoningMessageItem reasoning) {
            return new SnapshotMessage(role, KIND_REASONING, reasoning.getContent(),
                    null, null, null, null, null, null, reasoning.getRawContentJson(), tokens);
        }
        if (item instanceof AssistantMessageItem assistant) {
            List<ToolCall> calls = assistant.effectiveToolCalls();
            List<SnapshotToolCall> snapshotCalls = calls.isEmpty() ? List.of()
                    : calls.stream()
                    .map(c -> new SnapshotToolCall(c.toolCallId(), c.toolCode(), c.toolArgs()))
                    .toList();
            return new SnapshotMessage(role, KIND_ASSISTANT, assistant.getContent(),
                    assistant.getToolCallId(), assistant.getToolCode(), assistant.getToolArgs(),
                    snapshotCalls, null, null, null, tokens);
        }
        if (item instanceof ToolMessageItem tool) {
            return new SnapshotMessage(role, KIND_TOOL, tool.getExecResult(),
                    null, null, null, null, tool.getCallId(), tool.getToolCode(), null, tokens);
        }
        return new SnapshotMessage(role, "UNKNOWN", item.transfer2prompt(),
                null, null, null, null, null, null, null, tokens);
    }
}
