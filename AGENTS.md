# AGENTS.md

本文件是 openagent-harness 的**项目级指令记忆**，会在每次会话启动时由
`InstructionMemoryLoader` 自动装配进 system prompt（全局 `~/.openagent/AGENTS.md` →
仓库根 → 当前目录，逐级合并；支持 `@path` 引入其它文件）。

## 项目概述

openagent-harness 是一个用 Java 从零实现的 Agent Harness（智能体运行框架），
参考 Claude Code / Codex 等主流工程设计。核心是一个 while 循环的 Agent Loop：
`model.call() → tool.execute() → 结果反馈给模型`，直到模型给出最终答案。

## 技术栈与构建

- JDK 21（`maven.compiler.source/target=21`），构建工具 Maven。
- 本机默认 `JAVA_HOME` 可能是 JDK8，编译/运行前需切到 JDK21：
  `/Users/demus/Library/Java/JavaVirtualMachines/ms-21.0.12.1-1/Contents/Home`。
- 编译验证：`mvn -q compile`。
- 关键依赖：Jackson（序列化）、Log4j2（日志）、MyBatis-Plus + HikariCP + MySQL（事件落库）、Lombok。

## 架构约定（务必遵守）

- **事件日志是唯一真相源**：所有状态变更先 `applyEvent` 落库，运行期再增量维护
  `modelContext`；断点恢复由 `AgentState.resume()` 从事件流重建，不要在别处旁路维护状态。
- **两套运行时**：`AgentRuntime`/`AgentExecutor` 走 Chat Completions API（`Model`）；
  `AgentResponseRuntime`/`AgentResponseExecutor` 走 Responses API（`ResponseModel`）。改动需兼顾两者。
- **DeepSeek Responses API 分组约束**：同一轮内所有 `function_call` 必须连续排列、
  `function_call_output` 统一跟在其后，交错会触发 400。
- **上下文压缩**：单一阈值 `compaction.threshold`，超阈值先 L0 规则压缩、仍超再升级 L1 LLM 摘要。
- **工具结果外存**：超阈值的工具输出落盘到 `{workspace}/.agent-data/tool-results/{sessionId}/{callId}.txt`，
  上下文只保留引用存根，模型用 `read_tool_result` 分页读取；`read_tool_result` 自身输出必须豁免落盘（否则无限套娃）。

## 安全与可靠性（P0）

- **shell 安全双层**（`com.szh.tool.security`，总开关 `shell.security.enabled`）：所有 `ShellCommandTool`
  执行前先过 `PermissionController` 策略闸门，再由 `SandboxExecutor` 包装进 OS 级沙箱。
  - **权限层**（对标 Claude Code allow/ask/deny）：`CommandClassifier` 按「可执行文件+关键参数」把命令分为
    只读/写入/联网/危险；`PathGuard` 做路径穿越防护（`..` 归一化 + 真实路径包含校验）；决策三态 ALLOW/DENY/NEED_CONFIRM，
    确认走 `PermissionPrompter`（交互式读 stdin，非交互按 `shell.permission.autoApprove` 兜底，默认拒绝）。
  - **沙箱层**（对标 Codex，不用 Docker）：macOS 用系统自带 `sandbox-exec`/Seatbelt（`SeatbeltSandbox`），
    三档 `shell.sandbox.mode` = read-only / workspace-write / full-access；workspace-write 只放开
    `shell.sandbox.writable.extra`（含工作区、`~/.m2` 等构建缓存、临时目录）的写权限，默认禁网，
    仅 `shell.sandbox.network.tools`（git/mvn）豁免联网。其它平台降级为 `DirectSandbox`（靠权限层兜底）。
  - **关键坑**：macOS `/tmp`→`/private/tmp`、`/var/folders`→`/private/var/folders` 是符号链接，而 Seatbelt `subpath`
    与路径包含判断都按**规范化真实路径**匹配，故 `SandboxPolicy.real()`/`PathGuard.canonical()` 必须 `toRealPath()`
    解析符号链接，否则这些目录会被误判越界（表现为 mvn 写 jansi 锁文件 Operation not permitted）。
- **模型调用重试**（`com.szh.utils.RetryExecutor` + `RetryPolicy`）：两个 DeepSeek 模型都接入指数退避重试。
  非 200 抛 `ModelApiException` 携带状态码与 `Retry-After`；仅 429/408/5xx 与网络 IO 异常可重试，其余 4xx 立即失败。
  请求带 `model.request.timeoutSeconds` 超时。新增 Provider 务必复用 `RetryExecutor` 而非各写一套。
- **循环熔断**（`com.szh.agent.LoopGuard`，run 级实例）：检测「连续失败」（工具抛异常或返回错误结果达
  `agent.guard.maxConsecutiveFailures`）与「重复调用」（同工具+同参数连续达 `agent.guard.maxRepeatCalls`），
  触发即结束 run 并回传原因。两条运行时都接入：`AgentRuntime` 内联、`AgentResponseRuntime` 经 `HandleContext`
  传给 `FunctionCallHandler`。工具执行必须 try-catch 兜底，异常转错误结果计入熔断，不再让整个 run 崩溃。

## P1 核心体验（智能增强）

- **多模型 + 流式**（`com.szh.model`）：`OpenAiCompatModel`（Chat Completions）/`OpenAiCompatResponseModel`（Responses）
  是 OpenAI 兼容协议基类，DeepSeek 两模型只是其子类（固定 baseUrl/默认模型名）；`ModelFactory` 按 `model.provider`
  （deepseek/openai/ollama/vllm/lmstudio）与 `model.response.provider` 装配两条链路，apiKey 未配置时回退环境变量。
  SSE 流式走 `Model.call(messages, tools, StreamListener)` 重载，增量 token 由 `ConsoleStreamListener` 实时打到 stdout
  （`model.stream.enabled`/`model.stream.printReasoning`）；**流式路径不重试**（增量已回放无法收回），阻塞路径仍走 RetryExecutor。
- **文件工具**（`com.szh.tool.tools.file`，开关 `file.tools.enabled`）：`read_file`（带行号+offset/limit 分页）、
  `write_file`（全量创建/覆盖）、`edit_file`（精确字符串替换，old_text 必须唯一否则拒绝，支持 replace_all，结果回显 LCS 统一 diff）、
  `repo_map`（目录树+Java 符号抽取，自动剪枝 .git/target/node_modules 等噪音目录）。
  路径安全由 `FileToolSupport` 统一把关：复用 `PathGuard.resolve`（含符号链接规范化），读写默认仅限工作区内，
  `file.tools.allowOutside=true` 才放开到策略允许根；**读校验不能用 writableRoots 当边界**（含 /tmp 等，临时目录工作区会被穿越）。
- **并行工具调用**（`com.szh.agent.ParallelToolExecutor`，两条运行时共用）：一轮内多个调用并发执行
  （`agent.parallel.maxThreads`，单轮总超时 `agent.parallel.timeoutSeconds`），事件严格按「全部 CallToolStarted →
  并发执行 → 全部 CallToolFinished（按调用原序）」落库，满足 Responses API 分组约束；`AssistantMessageItem.toolCalls`
  承载一轮多调用（标量字段保留为首调用快照兼容旧路径）。配套线程安全：`AgentState.applyEvent`/`LoopGuard.record`/
  `ConsolePermissionPrompter.confirm` 均已 synchronized。
- **冒烟验证**：`com.szh.test.P1SmokeTest`（main 直跑，不依赖模型 API）覆盖文件工具链/路径安全/repo_map/并行事件有序/工厂装配。

## P2 交互与可观测性

- **交互式 REPL**（`com.szh.cli.Repl`，纯 JDK 无新增依赖）：把「一次性 run」升级为可持续对话的 CLI 会话。
  跨轮复用同一 `AgentState` + 运行时实例（上下文由 `applyEvent` 增量维护，MEMORY 引擎下也能多轮连续）；
  `/runtime chat|response` 双链路切换共享同一 session；`/new`、`/session [id]`、`/replay`、`/model`、`/clear` 等内置命令；
  默认链路走 `repl.runtime`。开启 `model.stream.enabled` 时正文已由 `ConsoleStreamListener` 逐 token 打到 stdout，REPL 不再重复打印最终结果。
  支持 `--prompt "..."` 非交互一次性执行；权限确认仍由 `ConsolePermissionPrompter` 直接读 stdin（REPL 一次只读一行、run 期间不并发读取，交互场景不串台）。
- **Git 工作流**（`com.szh.tool.tools.git`，开关 `git.tools.enabled`）：把 add/commit/push/status 封装成参数结构化独立工具
  （`git_add`/`git_commit`/`git_push`/`git_status`），修复「无法提交代码」——`git_commit` 强制 `-m` 且注入 `GIT_EDITOR=true` 杜绝裸 commit 弹编辑器阻塞，
  基类统一 `GIT_TERMINAL_PROMPT=0` 避免 push 缺凭据挂死；仓库缺身份时可开 `git.commit.autoIdentity` 用 `-c user.name/email` 兜底（会覆盖既有身份，慎用）。
  命令仍走 `ShellCommandTool` 权限闸门 + 沙箱；原始 `git` 透传工具保留供 diff/log/show 只读查询。子类经 `ShellCommandTool.environment()` 钩子注入环境变量。
- **Trace 回放**（`com.szh.trace.replay.TraceReplay`）：把 `EventStore` 事件流重渲染成 run→turn→round 的可读时间线，解决「调试困难」。
  `render()` 输出控制台文本时间线，`exportHtml()` 导出自包含 HTML（按事件类型着色、长内容折叠）到 `{trace.html.dir|workspace/.agent-data/traces}/{sessionId}.html`；
  只读不改事件真相源，REPL `/replay [id] [--html]` 与 `TraceReplay <sessionId> [--html]` main 均可触发。注意 token 用量未随事件落库，回放呈现流程与耗时不含逐轮 token。

## P3 子 agent / Task 派生（上下文物理隔离）

- **定位**（`com.szh.agent.subagent`，总开关 `subagent.enabled`）：对标 Claude Code 的 Task 工具，把「会产生大量中间产物、但主对话只需要一个结论」的子任务外包给独立上下文的子 agent，主 agent 只拿最终结论——这是长任务不炸上下文的核心手段，与既有的上下文压缩（L0→L1）、工具结果落盘互补（压缩有损、隔离无损）。
- **触发**：主 agent 调 `dispatch_subagent(type, prompt)` 工具（`com.szh.tool.tools.subagent.DispatchSubAgentTool`，注册进 `ToolRegistry`）。`prompt` 必填且**必须自包含**——子 agent 看不到主对话历史。`type` 见 `SubAgentType`：`general-purpose`（全部工具）/ `code-review`（只读工具子集，不改仓库）/ `explore`（只读检索），每种类型绑定一份「角色指令 + 工具白名单」；未知/不传回退 general-purpose。
- **执行**（`SubAgentExecutor.dispatch`）：为每次派生建**全新独立运行时环境**——独立 `EventStore` + 独立 `AgentState`（角色指令经 `AgentState(EventStore, rolePromptAppendix)` 追加在分层 system prompt 之后）+ 独立派生 `sessionId`（`{parent}-sub-{8位短id}`）+ 按类型白名单构造的 `ToolRegistry`；复用现有两条运行时（`subagent.runtime`=chat/response，Responses 不可用自动回退 chat），子 agent 用更小的 `subagent.maxRound` 收紧轮次。
- **关键约束（务必遵守）**：
  - **子 agent 事件绝不混入父事件流**——父 `AgentState.resume()` 从事件流重建 modelContext，若子事件混进去，断点恢复会把子 agent 全部中间产物灌回主上下文，直接违背「只拿最终结论」。子 agent 用自己的 sessionId 独立落库，父只通过工具结果拿结论；`TraceReplay` 天然按 session 分别回放父子时间线。
  - **递归深度靠结构杜绝**：子 registry 以 `childDepth=parentDepth+1` 构造，`ToolRegistry.subAgentTools()` 在 `subAgentDepth >= subagent.maxDepth`（默认 1）时**不注册** `dispatch_subagent`，子 agent 默认拿不到派生工具，无法再生孙 agent；`SubAgentExecutor` 另有 `childDepth > maxDepth` 兜底拒绝。
  - **工具白名单是唯一真实缺口**：`ToolRegistry` 新增 `ToolRegistry(Set<String> allowedCodes, int subAgentDepth)` 构造，rebuild 末尾按 `allowedCodes` 过滤（null=全量）。
- **已知取舍**：子 agent 继承全局 `model.stream.enabled` 与权限确认策略；并行派生多个含 shell 的子 agent 时，`ConsolePermissionPrompter` 读 stdin 可能串台，建议模型顺序派生或依赖 `shell.permission.autoApprove` 兜底。结论超长仍走既有落盘（`inlineResult` 默认 false）。
- **冒烟**：`com.szh.test.SubAgentSmokeTest`（main 直跑，注入 Fake 模型不依赖真实 API）覆盖类型解析/白名单过滤/深度闸门/事件隔离/端到端派生返回结论。

## MCP Client（外部工具生态）

- **定位**（`com.szh.mcp`，总开关 `mcp.enabled`）：实现 [Model Context Protocol](https://modelcontextprotocol.io/) 客户端，
  把外部 MCP Server（filesystem / github / postgres / slack / playwright 等）的 tools 动态注册进 `ToolRegistry`，
  让 openagent 一次接入即吃到整个 MCP 生态。第一版仅覆盖 stdio transport（对齐 Claude Desktop / Cursor 主流做法）；
  HTTP+SSE / Streamable HTTP 预留了 `McpTransport` 抽象接口，后续可按需扩展。
- **协议栈**（三层解耦）：`protocol/JsonRpc` 只做 JSON-RPC 2.0 消息构造与判别；`transport/StdioTransport`
  负责子进程 stdin/stdout NDJSON 收发（stdout 独立读线程按 id 路由 pending，stderr 独立线程落日志——两者都必须异步读干净，
  否则子进程缓冲区打满会直接卡死）；`client/McpClient` 处理 initialize 握手（协议版本 2025-06-18，老 Server 回退 2024-11-05）、
  tools/list 分页、tools/call 与 content[] 展平。反向 request（sampling/roots）当前不实现，由 StdioTransport 自动回 method_not_found，避免 Server 卡等。
- **配置**（`~/.openagent/mcp.json`，格式对齐 Claude Desktop，可被 `mcp.config.file` 覆盖）：
  ```json
  {"mcpServers": {"filesystem": {"command":"npx","args":["-y","@modelcontextprotocol/server-filesystem","/path"],
    "env":{"TOKEN":"${MY_TOKEN}"},"disabled":false,"sandbox":false,"connectTimeoutSeconds":20,"rpcTimeoutSeconds":60}}}
  ```
  `McpConfigLoader` 支持 `~` 展开与 `${VAR}` 环境变量替换（共享配置不必硬编码 secret）；单个 Server 解析失败不影响其它 Server。
- **生命周期**（`McpClientManager` 单例）：懒启动 `ensureInit()`（首次调才拉 Server）→ 并发启动 `STARTUP_PARALLELISM=4`
  → 单 Server 失败仅记入 `failures` 不拖垮整体 → JVM shutdown hook 保证退出时子进程不残留。REPL `/mcp reload`
  触发 `manager.reload() + toolRegistry.rebuild()`，用户改完 `mcp.json` 不需要重启会话。
- **安全**：Server 启动命令走 `ShellSecurity.authorize` 权限闸门（防配置被塞危险命令），但**默认不走沙箱包装**
  ——MCP Server 常需写 `~/.npm`、`~/.cache` 等目录，沙箱档位没配好反而启动失败；用户显式在配置里 `sandbox:true` 才 `ShellSecurity.wrap`。
- **命名空间**：MCP 工具 code = `{serverName}__{toolName}`（双下划线，对齐 Claude Code / Cursor），description 前缀 `[MCP:{server}]`
  让模型显式感知工具来源；OpenAI/DeepSeek 的 function.name 允许 `[a-zA-Z0-9_-]`，双下划线合法且与内置工具天然不冲突。
- **可观测**：REPL `/mcp` 显示已连接/失败/禁用概览，`/mcp tools` 按 Server 分组列全部工具 code + 描述；
  Server 通过 stderr 输出的日志会以 `mcp[{server}:stderr] ...` 前缀落到 log4j2，`notifications/message` 也会转成日志。
- **冒烟**：`com.szh.test.McpSmokeTest` 内嵌 Python echo Server，覆盖握手/tools 列表/tools 调用/isError/非法参数/Manager reload/工具包装 21 项断言，无需外部依赖。

## 元工具（agent 自组织）

- **定位**（`com.szh.tool.tools.meta`，总开关 `meta.tools.enabled`）：对标 Claude Code 的 TodoWrite / AskUserQuestion / SwitchMode，
  给 agent「自组织」能力——自己维护多步任务清单、结构化问询用户、在 NORMAL/PLAN 模式间切换，而不是只能一路 tool_call 到底。
  三件套：`todo_write` / `ask_user_question` / `switch_mode`，均继承 `MetaToolSupport`（统一入参解析 + `emit` 落事件 + `inlineResult=true` 回执内联不落盘）。
- **状态双写（关键约定）**：元工具改变的是 agent 的自组织状态而非外部世界。运行期待办/模式态各用一个 **session 键的进程内单例**
  （`TodoStore` / `AgentModeStore`，对齐 `LongTermMemory.get()` / `McpClientManager.get()` 范式）供 REPL 低延迟渲染与跨轮复用；
  同时把变更落成 **surface 事件**（`TodoUpdatedEvent` / `ModeSwitchedEvent`）进事件日志（唯一真相源），供 `/session` 恢复回灌与 TraceReplay 回放。
- **surface 事件不进模型上下文**：两个新事件刻意**不继承 `MessageEvent`**——清单/模式内容已通过工具结果回执给了模型，再塞进 `modelContext`
  既冗余又会破坏 DeepSeek Responses API 的 function_call 分组约束。故 `AgentState.resume()`/`deriveMessages()` 天然忽略它们，只由 REPL/回放消费。
- **工具如何拿到落库入口**：工具只拿得到 `ToolContext`，故约定运行时在 `ParallelToolExecutor.runSingle` 构造 `ToolContext` 时注入
  `AgentState` + `turnId` + `round`（新增字段），元工具经 `MetaToolSupport.emit(ctx, event)` 落库；脱离运行时的单测直调时 `AgentState` 为 null，`emit` 静默跳过、内存 store 仍更新，保证工具可独立测试。
- **PLAN 模式（`switch_mode`）**：会话级模式，两条运行时（`AgentRuntime`/`AgentResponseRuntime`）**每轮开头**读 `AgentModeStore`，
  经 `PlanModePolicy` 施加软硬双约束——①`effectiveTools` 用**拒绝名单**（write_file/edit_file/git_add/git_commit/git_push/mvn/remember/forget/dispatch_subagent）
  剔除写入类工具，只留只读检索 + 元工具；②`decorateContext` 往上下文副本的 system prompt 追加规划契约（只改副本不动真相源）。切换**下一轮生效**，无需重启会话。
  用拒绝名单而非白名单：默认放行、仅剔已知写入工具，新增只读工具无需改这里，底层仍有权限闸门 + 沙箱兜底。用户也可在 REPL 用 `/mode plan|normal` 切换（写同一份 store + 落同一事件）。
- **ask_user_question**：结构化多选/单选问询，交互复用 `ConsoleQuestionPrompter` 读 stdin（沿用 `ConsolePermissionPrompter` 约定：提示走 stderr、
  `synchronized` 防并行串台、run 期间 REPL 不并发读）；非交互（EOF，如 `--prompt`/单测）返回 `null`，工具据此回「无人可答」提示引导模型自行决策而非死等。不落事件（即时交互，答案经工具结果回传即可）。
- **REPL 渲染**：`/todo` 查看当前清单，每轮对话结束后自动渲染待办（若有）；`/mode [plan|normal]` 查看/切换模式，banner 显示当前模式；
  `/session <id>` 恢复时 `restoreMetaState()` 扫事件流回灌 `TodoStore`/`AgentModeStore`。TraceReplay 文本时间线与 HTML 均新增 TODO/MODE 事件渲染（各自着色）。
- **冒烟**：`com.szh.test.MetaSmokeTest`（main 直跑，注入 Fake 问询器 + 进程内事件存储，不依赖真实 API/stdin）33 项断言全过：
  todo 替换/merge/状态归一化/渲染/事件落库、事件编解码往返、问询解析/结构化回执/非交互兜底、模式切换/PLAN 工具过滤、registry 装配。

## 存储引擎

由 `store.engine` 切换：`MEMORY`（进程内、非持久）、`MYSQL`（落库）、
`FILE`（Codex 风格 JSONL rollout，落 `~/.openagent/sessions/{sessionId}.jsonl`）。

## 代码风格

- 包根为 `com.szh`；新增工具放到 `com.szh.tool.tools` 下并注册进 `ToolRegistry`。
- 中文注释，解释“为什么”而非“是什么”；对外配置项走 `ConfigUtil` 统一读取。
