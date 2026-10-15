package com.szh.trace.context;

/**
 * 上下文快照中的一次工具调用（中性 DTO）。
 * <p>
 * 快照刻意不直接序列化 {@code AssistantMessageItem} 等 {@code MessageItem} 实现：{@code MessageItem} 是接口，
 * 无类型信息，Jackson 按接口序列化会丢掉全部字段。转成这组扁平 DTO 后落盘，等于「写侧/读侧（Web 查看器）」
 * 之间的稳定契约，后续即便上下文 DTO 内部结构调整，查看器也不必跟着改。
 *
 * @author demussong
 * @date 2026/10/15
 */
public record SnapshotToolCall(String id, String code, String args) {
}
