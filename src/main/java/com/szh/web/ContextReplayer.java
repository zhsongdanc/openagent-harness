package com.szh.web;

import com.szh.context.InstructionMemoryLoader;
import com.szh.context.dto.MessageItem;
import com.szh.context.dto.SystemMessageItem;
import com.szh.event.ContextCompactedEvent;
import com.szh.event.Event;
import com.szh.event.EventEnum;
import com.szh.event.MessageEvent;
import com.szh.trace.context.SnapshotMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 上下文重建器（快照缺失时的回退路径）：只凭事件流重建「某一轮实际发给模型的上下文」。
 * <p>
 * 口径与 {@code AgentState.resume()} / {@code deriveMessages()} <b>刻意保持一致</b>，否则查看器展示的上下文
 * 会和真实恢复出来的上下文不一致、失去参考价值：
 * <ul>
 *   <li>只有 {@link MessageEvent} 会进入上下文（surface 事件天然被跳过）；</li>
 *   <li>遇到 {@link ContextCompactedEvent} 检查点即把此前历史折叠为 {@code [system, 摘要, 尾部 keepRecentCount 条]}，</li>
 *       多次压缩按事件顺序增量叠加。</li>
 * </ul>
 * <b>局限（UI 会显式标注）</b>：system prompt 由 {@code InstructionMemoryLoader} 按当前工作区重新装配，
 * 与运行当时可能因 AGENTS.md 变更而不同；运行期软装饰（记忆召回 / 规划契约 / 验证提示 / 护栏纠偏）不落事件，
 * 无法还原。要 100% 原文请依赖 {@link ContextSnapshotReader} 的侧车快照。
 *
 * @author demussong
 * @date 2026/10/15
 */
public class ContextReplayer {

    /**
     * 重建结果
     *
     * @param messages       展平后的消息列表（与快照同构，前端可统一渲染）
     * @param estimatedTokens 逐条估算值之和
     */
    public record Rebuilt(List<SnapshotMessage> messages, int estimatedTokens) {
    }

    /**
     * 重建目标轮次的上下文；事件流里找不到该 run 时返回 null。
     * <p>
     * 「目标轮上下文」= 该 run 该轮模型调用<b>发起前</b>的上下文，故按时间顺序重放，
     * 一走到「属于目标轮且发生在模型调用之后」的第一个事件就停下取快照；
     * 走到目标 run 之后的 run 同样立即停下（那些消息在本次调用时还不存在）。
     */
    public Rebuilt rebuild(List<Event> events, String targetRunId, int targetRound) {
        if (events == null || targetRunId == null) {
            return null;
        }
        List<MessageItem> context = new ArrayList<>();
        // 起点与 AgentState 一致：分层装配的 system prompt
        context.add(new SystemMessageItem(InstructionMemoryLoader.loadSystemPrompt()));

        String currentRun = null;
        boolean reached = false;
        for (Event event : events) {
            if (!Objects.equals(currentRun, event.getRunId())) {
                if (Objects.equals(event.getRunId(), targetRunId)) {
                    reached = true;
                } else if (reached) {
                    break;
                }
                currentRun = event.getRunId();
            }
            if (reached && event.getRound() == targetRound
                    && event.getType() != EventEnum.CONTEXT_COMPACTED) {
                // 该轮模型调用已发起（压缩检查点更早，已在上方处理），此刻上下文即为"送给模型的上下文"
                break;
            }
            apply(context, event);
        }
        if (!reached) {
            return null;
        }
        List<SnapshotMessage> messages = context.stream().map(SnapshotMessage::from).toList();
        int tokens = messages.stream().mapToInt(SnapshotMessage::estimatedTokens).sum();
        return new Rebuilt(messages, tokens);
    }

    /**
     * 单事件对上下文的作用：MessageEvent 追加，压缩检查点折叠
     */
    private void apply(List<MessageItem> context, Event event) {
        if (event instanceof MessageEvent messageEvent) {
            context.add(messageEvent.getMessageItem());
            return;
        }
        if (event instanceof ContextCompactedEvent compacted) {
            collapse(context, compacted);
        }
    }

    /**
     * 折叠压缩历史：{@code [system, 摘要, 尾部 keepRecentCount 条]}（与 {@code AgentState.collapseCompacted} 同口径）
     */
    private void collapse(List<MessageItem> context, ContextCompactedEvent event) {
        String summary = event.getSummary();
        if (summary == null || context.isEmpty()) {
            return;
        }
        MessageItem system = context.get(0);
        int bodySize = context.size() - 1;
        int keep = Math.max(0, Math.min(event.getKeepRecentCount(), bodySize));
        List<MessageItem> rebuilt = new ArrayList<>(keep + 2);
        rebuilt.add(system);
        rebuilt.add(new SystemMessageItem(summary));
        rebuilt.addAll(context.subList(context.size() - keep, context.size()));
        context.clear();
        context.addAll(rebuilt);
    }
}
