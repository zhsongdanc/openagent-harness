package com.szh.trace.context;

import java.util.List;

/**
 * 「某一轮实际送给大模型的上下文」的完整快照。
 * <p>
 * 为什么单独存：事件日志是唯一真相源，但 system prompt 由 {@code AgentState} 构造时装配、各轮运行期软装饰
 * （记忆召回 / 规划契约 / 验证提示 / 护栏纠偏）只改上下文副本，都不落事件——事后仅靠事件流只能重建出
 * 「消息历史」这个近似，拿不到 100% 原始请求。故在 {@code model.call} 前把 {@code callContext} 原样另存一份，
 * 与事件日志<b>物理分离</b>（事件日志一行不增、断点恢复语义零影响），查看器要精确原文就读它。
 *
 * @param sessionId       会话 id
 * @param runId           所属 run
 * @param turnId          所属轮次（turn_N）
 * @param round           run 内的轮次序号
 * @param timestamp       落盘时间（≈ 模型调用发起时刻）
 * @param messageCount    消息条数（= messages.size()）
 * @param estimatedTokens 本地估算的上下文 token 数（与 {@code StepCompactor.estimateTotalTokens} 同口径）
 * @param tools           本轮模型可见的工具 code 列表（PLAN 模式下会被收敛为只读子集，故逐轮记录）
 * @param messages        逐条消息的展平快照
 * @author demussong
 * @date 2026/10/15
 */
public record ContextSnapshot(
        String sessionId,
        String runId,
        String turnId,
        int round,
        long timestamp,
        int messageCount,
        int estimatedTokens,
        List<String> tools,
        List<SnapshotMessage> messages
) {
}
