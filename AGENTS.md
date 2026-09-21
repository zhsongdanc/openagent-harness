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

## 存储引擎

由 `store.engine` 切换：`MEMORY`（进程内、非持久）、`MYSQL`（落库）、
`FILE`（Codex 风格 JSONL rollout，落 `~/.openagent/sessions/{sessionId}.jsonl`）。

## 代码风格

- 包根为 `com.szh`；新增工具放到 `com.szh.tool.tools` 下并注册进 `ToolRegistry`。
- 中文注释，解释“为什么”而非“是什么”；对外配置项走 `ConfigUtil` 统一读取。
