package com.szh.mcp.client;

import lombok.Data;

/**
 * @author demussong
 * @describe MCP tools/list 返回的单个工具元信息，只保留 openagent 侧需要的字段。
 * <p>
 * {@code inputSchemaJson} 保留原始 JSON Schema 字符串而不解析成对象，是因为
 * {@link com.szh.tool.ToolDefinition#getParameters()} 就是 JSON Schema 字符串口径，
 * 直接透传零转换；模型侧的 tool schema 与本地文件工具保持完全一致。
 * @date 2026/9/22
 */
@Data
public class McpToolInfo {

    /** MCP Server 内部工具名，与 openagent 侧的 tool code 差一个「server 前缀」 */
    private String name;

    /** 工具描述，会拼上「[MCP:{server}]」前缀让模型能识别来源 */
    private String description;

    /** 原始 JSON Schema 字符串，形如 {@code {"type":"object","properties":{...},"required":[...]}} */
    private String inputSchemaJson;
}
