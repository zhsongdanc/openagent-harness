package com.szh.store;

/**
 * 事件的可移植序列化快照：承载重建一个 {@link com.szh.event.Event} 所需的全部字段。
 * <p>
 * 与存储介质解耦——{@code MySqlEventStore} 把它拆进各列，{@code FileEventStore} 直接把它
 * 作为一行 JSON（JSONL）落盘。payload 为业务数据的 JSON 字符串（无业务数据时为 null）。
 *
 * @author demussong
 * @date 2026/9/21
 */
public record SerializedEvent(
        String eventId,
        long timestamp,
        String sessionId,
        String runId,
        String turnId,
        int round,
        String eventType,
        String payload
) {
}
