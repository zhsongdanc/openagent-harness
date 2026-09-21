package com.szh.mcp.tool;

import com.szh.mcp.client.McpClient;
import com.szh.mcp.client.McpToolInfo;
import com.szh.tool.Tool;
import com.szh.tool.ToolContext;
import com.szh.tool.ToolDefinition;
import lombok.extern.slf4j.Slf4j;

/**
 * @author demussong
 * @describe 把 MCP Server 暴露的工具包装成 openagent 的 {@link Tool}，让模型侧完全无感——
 * 与本地文件工具、shell 工具走同一条 tool_call 通路。
 * <p>
 * 命名规则：{@code {serverName}__{toolName}}（双下划线，对齐 Claude Code / Cursor 惯例）。
 * 好处：
 * <ol>
 *   <li>与内置工具天然隔离，即便 MCP filesystem server 的 {@code read_file} 也不会撞内置的 {@code read_file}；</li>
 *   <li>模型看到前缀能推断工具来源，减少「以为是本地工具」的幻觉；</li>
 *   <li>OpenAI / DeepSeek 的 function.name 允许 {@code [a-zA-Z0-9_-]}，双下划线合法。</li>
 * </ol>
 * 描述前缀 {@code [MCP:{server}]} 也用于让模型显式感知这是外部工具，需要更严格的参数校验。
 * @date 2026/9/22
 */
@Slf4j
public class McpTool implements Tool {

    /** server 与 tool 之间的分隔符；OpenAI function.name 允许 [a-zA-Z0-9_-]，双下划线安全 */
    public static final String SEPARATOR = "__";

    private final McpClient client;
    private final McpToolInfo info;
    private final String code;

    public McpTool(McpClient client, McpToolInfo info) {
        this.client = client;
        this.info = info;
        this.code = client.getServerName() + SEPARATOR + info.getName();
    }

    @Override
    public String getCode() {
        return code;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        String desc = info.getDescription() == null ? "" : info.getDescription();
        return ToolDefinition.builder()
                .name(code)
                .code(code)
                .type("mcp")
                .description("[MCP:" + client.getServerName() + "] " + desc)
                // 直接把 Server 给的 JSON Schema 字符串透传，OpenAiCompatModel.convertTools 里 readTree 一次即可
                .parameters(info.getInputSchemaJson())
                .build();
    }

    @Override
    public String execute(ToolContext toolContext) {
        // 具体协议异常、超时、Server 崩溃都在 client 里转成错误文本回传，本层不再 catch
        return client.callTool(info.getName(), toolContext.getArgs());
    }
}
