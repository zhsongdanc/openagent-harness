# 交互 REPL 与可观测性（L1 按需记忆）

> 命中条件：改动 `com.szh.cli.Repl` / `com.szh.trace` / `com.szh.web` / 新增 `EventEnum` 后的回放渲染，
> 或遇到「回放看不到某事件」「Web Viewer 接口 500」「上下文快照与重建对不上」时，**先读完本文再动手**。

## 交互式 REPL（`com.szh.cli.Repl`，纯 JDK 无新增依赖）

把「一次性 run」升级为可持续对话的 CLI 会话：

- 跨轮复用同一 `AgentState` + 运行时实例（上下文由 `applyEvent` 增量维护，MEMORY 引擎下也能多轮连续）；
- `/runtime chat|response` 双链路切换共享同一 session；`/new`、`/session [id]`、`/replay`、`/model`、
  `/clear`、`/memory` 等内置命令；默认链路走 `repl.runtime`；
- 开启 `model.stream.enabled` 时正文已由 `ConsoleStreamListener` 逐 token 打到 stdout，REPL 不再重复打印最终结果；
- 支持 `--prompt "..."` 非交互一次性执行、`--no-project-memory` 关闭项目层指令记忆；
  权限确认仍由 `ConsolePermissionPrompter` 直接读 stdin（REPL 一次只读一行、run 期间不并发读取，交互场景不串台）。

## Trace 回放（`com.szh.trace.replay.TraceReplay`）

把 `EventStore` 事件流重渲染成 run→turn→round 的可读时间线，解决「调试困难」：

- `render()` 输出控制台文本时间线，`exportHtml()` 导出自包含 HTML（按事件类型着色、长内容折叠）
  到 `{trace.html.dir|workspace/.agent-data/traces}/{sessionId}.html`；
- 只读不改事件真相源，REPL `/replay [id] [--html]` 与 `TraceReplay <sessionId> [--html]` main 均可触发；
- token 用量已随 `TokenUsageEvent` 落库，回放可呈现逐轮 token 与耗时。

## 每轮 token 落库（`TokenUsageEvent`，`EventEnum.TOKEN_USAGE`）

两条运行时在 `model.call` 后紧跟一条 **surface 事件**（**不继承 `MessageEvent`**、不进 `modelContext`、
被 `resume()` 天然忽略，故不破坏 DeepSeek Responses API 的 function_call 分组约束），字段含
prompt/completion/total/**cached**、上下文条数与估算 token、模型耗时（`elapsedMs`）。
`usage=null`（FakeModel / 未回 usage 的 provider）兜底为 0。

> ⚠️ `EventJsonCodec.deserialize` 与 `TraceReplay` 的 describe/HTML 都是对 `EventEnum` 的**穷尽 switch**，
> 加枚举值必须同时补齐这几处，否则编译直接报错（这是刻意保留的强制对齐）。

> 前端注意：`cached=0`（真的没命中）与「未记录」是两回事，区分依据是 `tokenRounds` 计数，不能靠值判断。

## 上下文快照侧车（`com.szh.trace.context`）

system prompt 与运行期软装饰（记忆召回 / PLAN 契约 / 验证提示 / 护栏纠偏）都不落事件，
事后仅靠事件流只能重建近似值。故两条运行时在 `model.call` **前**把真正的 `callContext` + 本轮可见
工具列表另存一份：

- 路径 `{trace.context.dir}/{sessionId}/{runId}.jsonl`（默认 `~/.openagent/context`，**全局而非工作区**，
  与 `store.file.dir` 同哲学），一行一轮；
- 开关 `trace.context.enabled`（默认 true），单条超 `trace.context.max.bytes`（默认 2MB）只告警跳过，
  **永不抛异常**（与 `ToolResultStore` 同款防御，诊断能力不许拖垮 run）；目录可随意删，删了自动回退重建；
- 快照**刻意不直接序列化 `MessageItem`**（接口无类型信息，Jackson 会丢字段），转成中性 DTO
  `SnapshotMessage`/`SnapshotToolCall` 落盘，等于写侧/读侧的稳定契约；
- 子 agent 用自己的 sessionId 落自己的目录，天然隔离。

## 读侧两档（UI 用徽标明确区分，不误导）

- `ContextSnapshotReader` 取精确快照（「当时真相」）；
- 缺失时 `ContextReplayer` 按与 `AgentState.deriveMessages()/collapseCompacted()` **完全一致的口径**
  从事件流重建（只累积 `MessageEvent` + 遇 `ContextCompactedEvent` 折叠为
  `[system, 摘要, 尾部 keepRecentCount]`，system prompt 按当前工作区重新装配）。
  **改 `AgentState` 的口径时必须同步改这里**（类注释已标注）。

## 本地 Web Viewer（`com.szh.web`，零新增依赖）

把「这次执行到底发生了什么」做成一个本地网页——左栏会话列表 → 会话内按 **turn → run → round** 下钻，
每轮可看**送给大模型的完整上下文**、**本轮 token 用量/耗时**、每次工具调用的入参与结果，以及
VERIFY/COMPACT/TODO/MODE 标记。用 JDK 内置 `com.sun.net.httpserver.HttpServer` + 原生 JS 单页
（无 CDN、无构建），**纯只读**：只读事件流与快照，从不写入、不参与 agent 运行、不需要 API Key，
只绑 `127.0.0.1`、无鉴权。

- **启动**：`bin/openagent-web [--port 8787] [--workspace /path]`
  （或 `java -cp openagent.jar com.szh.web.TraceWebServer`），端口配置 `web.server.host/web.server.port`。
  **不改 `Repl`**，与主流程完全解耦。
- **API（全 GET、`Cache-Control: no-store`、异常统一 JSON）**：`/api/meta`、`/api/sessions`、
  `/api/sessions/{id}`（**默认不带上下文正文**）、`/api/sessions/{id}/context?runId=&round=`（懒加载）、
  `/api/sessions/{id}/events?offset=&limit=`（原始事件，调试）、`/api/tool-result?session=&callId=`。
  所有来自 URL 的 id 参与路径拼接前一律过 `SessionCatalog.sanitize`（`[^a-zA-Z0-9_-]`→`_`，与 `FileEventStore` 同款）。
- **数据入口**：`SessionCatalog` 是查看器唯一数据入口——FILE 扫 `{store.file.dir}/*.jsonl`
  （依赖 `FileEventStore.getSessionDir()`），MYSQL 用一条 `group by session_id` 聚合（不新增 mapper 方法），
  MEMORY 返回空并提示「进程内非持久」；按「文件大小 + mtime」缓存解析结果，因为前端默认 3s 轮询
  而单个会话可达数百 KB。超长工具输出只给「查看完整结果」按钮按需拉 `/api/tool-result`
  （正文在**工作区**内，跨工作区会话需切目录启动）。
- **历史会话兼容**：老 JSONL 无 `TOKEN_USAGE` 类型不受影响，token 面板显示「未记录」，上下文自动走重建路径。

## 冒烟

`com.szh.test.WebViewerSmokeTest`（main 直跑，全部落盘指到临时目录，不依赖真实模型/MySQL/stdin）85 项断言全过：
会话清单与子会话打标 / turn→run→round 分组 / 并行工具配对 / token 汇总与「未记录 vs 0」区分 /
快照写读与超限·关开关兜底 / 压缩前后上下文重建折叠 / HTTP 各接口 / 路径穿越被拒 / `usage=null` 不 NPE。
