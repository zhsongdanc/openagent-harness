# MCP Client（外部工具生态，L1 按需记忆）

> 命中条件：改动 `com.szh.mcp` 下任何代码、调整 `mcp.*` 配置、或遇到
> 「MCP Server 起不来 / 卡死 / 工具没注册进 registry」时，**先读完本文再动手**。

## 定位（总开关 `mcp.enabled`）

实现 [Model Context Protocol](https://modelcontextprotocol.io/) 客户端，把外部 MCP Server
（filesystem / github / postgres / slack / playwright 等）的 tools 动态注册进 `ToolRegistry`，
让 openagent 一次接入即吃到整个 MCP 生态。第一版仅覆盖 stdio transport（对齐 Claude Desktop / Cursor
主流做法）；HTTP+SSE / Streamable HTTP 预留了 `McpTransport` 抽象接口，后续可按需扩展。

## 协议栈（三层解耦）

- `protocol/JsonRpc` 只做 JSON-RPC 2.0 消息构造与判别；
- `transport/StdioTransport` 负责子进程 stdin/stdout NDJSON 收发——**stdout 独立读线程按 id 路由 pending，
  stderr 独立线程落日志，两者都必须异步读干净**，否则子进程缓冲区打满会直接卡死；
- `client/McpClient` 处理 initialize 握手（协议版本 2025-06-18，老 Server 回退 2024-11-05）、
  tools/list 分页、tools/call 与 content[] 展平。
- 反向 request（sampling/roots）当前不实现，由 StdioTransport 自动回 method_not_found，避免 Server 卡等。

## 配置（`~/.openagent/mcp.json`，可被 `mcp.config.file` 覆盖，格式对齐 Claude Desktop）

```json
{"mcpServers": {"filesystem": {"command":"npx","args":["-y","@modelcontextprotocol/server-filesystem","/path"],
  "env":{"TOKEN":"${MY_TOKEN}"},"disabled":false,"sandbox":false,"connectTimeoutSeconds":20,"rpcTimeoutSeconds":60}}}
```

`McpConfigLoader` 支持 `~` 展开与 `${VAR}` 环境变量替换（共享配置不必硬编码 secret）；
单个 Server 解析失败不影响其它 Server。

## 生命周期（`McpClientManager` 单例）

懒启动 `ensureInit()`（首次调才拉 Server）→ 并发启动 `STARTUP_PARALLELISM=4` → 单 Server 失败仅记入
`failures` 不拖垮整体 → JVM shutdown hook 保证退出时子进程不残留。REPL `/mcp reload` 触发
`manager.reload() + toolRegistry.rebuild()`，用户改完 `mcp.json` 不需要重启会话。

## 安全

Server 启动命令走 `ShellSecurity.authorize` 权限闸门（防配置被塞危险命令），但**默认不走沙箱包装**
——MCP Server 常需写 `~/.npm`、`~/.cache` 等目录，沙箱档位没配好反而启动失败；
用户显式在配置里 `sandbox:true` 才 `ShellSecurity.wrap`。

## 命名空间

MCP 工具 code = `{serverName}__{toolName}`（双下划线，对齐 Claude Code / Cursor），description 前缀
`[MCP:{server}]` 让模型显式感知工具来源；OpenAI/DeepSeek 的 function.name 允许 `[a-zA-Z0-9_-]`，
双下划线合法且与内置工具天然不冲突。

## 可观测

REPL `/mcp` 显示已连接/失败/禁用概览，`/mcp tools` 按 Server 分组列全部工具 code + 描述；
Server 通过 stderr 输出的日志会以 `mcp[{server}:stderr] ...` 前缀落到 log4j2，
`notifications/message` 也会转成日志。

## 冒烟

`com.szh.test.McpSmokeTest` 内嵌 Python echo Server，覆盖握手 / tools 列表 / tools 调用 / isError /
非法参数 / Manager reload / 工具包装 21 项断言，无需外部依赖。
