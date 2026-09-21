package com.szh.mcp.config;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @author demussong
 * @describe 单个 MCP Server 的启动配置（stdio transport），字段命名对齐 Claude Desktop 的 mcp.json，
 * 用户可以直接把 Claude Desktop / Cursor 里的配置搬过来。
 * <p>
 * 关键字段：
 * <ul>
 *   <li>{@code command + args}：子进程启动命令，如 {@code npx -y @modelcontextprotocol/server-filesystem /tmp}；</li>
 *   <li>{@code env}：追加到子进程环境变量，常用来塞 API key（{@code GITHUB_TOKEN} 等）；</li>
 *   <li>{@code cwd}：子进程工作目录，缺省继承 openagent 的 {@code project.workspace}；</li>
 *   <li>{@code disabled}：临时禁用某 Server 但保留配置，避免频繁改文件；</li>
 *   <li>{@code sandbox}：是否走 {@code SandboxExecutor} 沙箱包装，默认关（Server 通常需写 ~/.npm、~/.cache 等目录，
 *       沙箱档位没配好反而会启动失败；用户显式开启才 wrap）。</li>
 * </ul>
 * @date 2026/9/22
 */
@Data
public class McpServerConfig {

    /** 配置文件里 mcpServers 的 key，用作 Server 名 + 工具 code 前缀 */
    private String name;

    /** 可执行文件（如 npx / node / python / uvx / 绝对路径） */
    private String command;

    /** 命令参数数组，不做 shell 解释 */
    private List<String> args = List.of();

    /** 追加到子进程的环境变量 */
    private Map<String, String> env = new LinkedHashMap<>();

    /** 子进程工作目录，缺省走 project.workspace */
    private String cwd;

    /** 是否禁用；true 时 Manager 会跳过启动，仍保留在配置里 */
    private boolean disabled;

    /** 是否走沙箱包装（默认关，理由见类注释） */
    private boolean sandbox;

    /** 启动 + initialize 握手的超时秒数；<=0 表示用全局默认 */
    private int connectTimeoutSeconds;

    /** 单次 RPC（tools/list、tools/call）超时秒数；<=0 表示用全局默认 */
    private int rpcTimeoutSeconds;
}
