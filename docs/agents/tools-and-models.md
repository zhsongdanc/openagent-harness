# 模型链路与工具体系（L1 按需记忆）

> 命中条件：改动 `com.szh.model` / `com.szh.tool` 下任何代码、新增工具、调整 `file.*` / `git.*` /
> `agent.parallel.*` / `tool.probe.*` 配置时，**先读完本文再动手**。

## 多模型 + 流式（`com.szh.model`）

- `OpenAiCompatModel`（Chat Completions）/ `OpenAiCompatResponseModel`（Responses）是 OpenAI 兼容协议基类，
  DeepSeek 两模型只是其子类（固定 baseUrl / 默认模型名）；`ModelFactory` 按 `model.provider`
  （deepseek/openai/ollama/vllm/lmstudio）与 `model.response.provider` 装配两条链路，
  apiKey 未配置时回退环境变量。
- SSE 流式走 `Model.call(messages, tools, StreamListener)` 重载，增量 token 由 `ConsoleStreamListener`
  实时打到 stdout（`model.stream.enabled` / `model.stream.printReasoning`）。
- **流式路径不重试**（增量已回放无法收回），阻塞路径仍走 RetryExecutor。
- 协议兼容基类改动会同时影响所有 provider 子类，改完至少跑一遍两条链路的冒烟。

## 文件工具（`com.szh.tool.tools.file`，开关 `file.tools.enabled`）

- `read_file`（带行号 + offset/limit 分页）、`write_file`（全量创建/覆盖）、
  `edit_file`（精确字符串替换，old_text 必须唯一否则拒绝，支持 replace_all，结果回显 LCS 统一 diff）、
  `repo_map`（目录树 + Java 符号抽取，自动剪枝 .git/target/node_modules 等噪音目录）、
  `delete_file`（删单个文件，破坏性操作，单独开关 `file.delete.enabled`）。
- 路径安全由 `FileToolSupport` 统一把关：复用 `PathGuard.resolve`（含符号链接规范化），
  读写默认仅限工作区内，`file.tools.allowOutside=true` 才放开到策略允许根；
  **读校验不能用 `writableRoots` 当边界**（含 /tmp 等，临时目录工作区会被穿越）。
- `delete_file` 在工具层加三道硬限制：
  1. 路径必须在 repo root 之内（以 `SandboxPolicy.workspaceRoot` 为边界，**不受** `file.tools.allowOutside` 放开）；
  2. 禁止目录删除（只删单文件）；
  3. 敏感删除人工确认——命中大文件（`file.delete.confirm.minBytes`）或特定后缀/文件名
     （`file.delete.confirm.suffixes`，默认 `.java,pom.xml`）时复用 `ConsoleQuestionPrompter`
     走 `ask_user_question` 同款交互征求确认；临时/测试文件命中豁免正则
     （`file.delete.confirm.exempt.regex`）自动放行；非交互（EOF）按 `file.delete.confirm.autoApprove`
     兜底（默认拒绝）。
- 删除前 `captureSnapshot` 拍 before-image，`/rewind` 可还原误删。

## 并行工具调用（`com.szh.agent.ParallelToolExecutor`，两条运行时共用）

- 一轮内多个调用并发执行（`agent.parallel.maxThreads`，单轮总超时 `agent.parallel.timeoutSeconds`）。
- 事件严格按「全部 CallToolStarted → 并发执行 → 全部 CallToolFinished（按调用原序）」落库，
  满足 Responses API 分组约束。
- `AssistantMessageItem.toolCalls` 承载一轮多调用（标量字段保留为首调用快照兼容旧路径）。
- 配套线程安全：`AgentState.applyEvent` / `LoopGuard.record` / `ConsolePermissionPrompter.confirm` 均已 `synchronized`。

## 工具能力探测按需注册（`com.szh.tool.BinaryProbe`，开关 `tool.probe.binaries.enabled`）

- `Tool` 有 `requiredBinaries()` 声明依赖的外部命令（裸 `GitTool`/`MvnTool`、`AbstractGitWorkflowTool`
  都声明 `git`，coreutils 不声明避免误判 shell 内建）。
- `ToolRegistry.rebuild()` 末尾统一一道过滤：白名单过滤后，再剔除本机 PATH 缺任一命令的工具
  （纯 `File` stat 扫描 PATH 不起子进程、结果进程内缓存，开关闭 / PATH 不可得时一律视为可用不误滤），
  被剔除的记 info 日志。补上裸 git/mvn 无 config 开关的漏口，避免在无 git/mvn 的环境
  （如 terminal-bench 纯 Python 容器）把不可用命令暴露给模型诱发无谓试错。
- 执行层互补：`ShellCommandTool.translateExecError` 在探测关闭或命令中途消失时，把
  `Cannot run program ... error: 2` 转为「命令未安装、勿重试、改用环境已有手段」的友好回执。
- 另有一条并行策略：**软引导写在 description 里**（`【适用前提】` 前缀），不在 run 起始做动态选型、
  也不引入惰性加载——少量内置工具的 schema token 开销远小于引入选型机制的复杂度与误裁风险。

## Git 工作流（`com.szh.tool.tools.git`，开关 `git.tools.enabled`）

- 把 add/commit/push/status 封装成参数结构化独立工具（`git_add`/`git_commit`/`git_push`/`git_status`），
  修复「无法提交代码」：
  - `git_commit` 强制 `-m` 且注入 `GIT_EDITOR=true` 杜绝裸 commit 弹编辑器阻塞；
  - 基类统一 `GIT_TERMINAL_PROMPT=0` 避免 push 缺凭据挂死；
  - 仓库缺身份时可开 `git.commit.autoIdentity` 用 `-c user.name/email` 兜底（会覆盖既有身份，慎用）。
- 命令仍走 `ShellCommandTool` 权限闸门 + 沙箱；原始 `git` 透传工具保留供 diff/log/show 只读查询。
- 子类经 `ShellCommandTool.environment()` 钩子注入环境变量。

## 冒烟

`com.szh.test.P1SmokeTest`（main 直跑，不依赖模型 API）覆盖文件工具链 / 路径安全 / repo_map /
并行事件有序 / 工厂装配。
