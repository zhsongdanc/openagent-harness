package com.szh.model.dto;

/**
 * @author demussong
 * @describe 单次工具调用描述：模型一轮回复可能并行请求多个工具调用，
 * 每个调用由 (callId, 工具名, JSON 参数) 三元组唯一确定。
 * 用 record 承载，可直接被 Jackson 序列化进事件日志（断点恢复时随
 * AssistantMessageItem 一起还原）。
 * @date 2026/9/21
 */
public record ToolCall(String toolCallId, String toolCode, String toolArgs) {
}
