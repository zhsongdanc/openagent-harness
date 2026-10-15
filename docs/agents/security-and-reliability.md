# 安全与可靠性（P0，L1 按需记忆）

> 命中条件：改动 `com.szh.tool.security` / `ShellCommandTool` / 任何新增会执行外部命令的工具 /
> `com.szh.utils.RetryExecutor` / `LoopGuard`，或遇到「mvn 在沙箱里 Operation not permitted」
> 「路径被判越界」「run 被莫名熔断」时，**先读完本文再动手**。

## shell 安全双层（总开关 `shell.security.enabled`）

所有 `ShellCommandTool` 执行前先过 `PermissionController` 策略闸门，再由 `SandboxExecutor`
包装进 OS 级沙箱。

- **权限层**（对标 Claude Code allow/ask/deny）：`CommandClassifier` 按「可执行文件 + 关键参数」把命令分为
  只读 / 写入 / 联网 / 危险；`PathGuard` 做路径穿越防护（`..` 归一化 + 真实路径包含校验）；
  决策三态 ALLOW/DENY/NEED_CONFIRM，确认走 `PermissionPrompter`（交互式读 stdin，
  非交互按 `shell.permission.autoApprove` 兜底，默认拒绝）。
- **沙箱层**（对标 Codex，不用 Docker）：macOS 用系统自带 `sandbox-exec`/Seatbelt（`SeatbeltSandbox`），
  三档 `shell.sandbox.mode` = read-only / workspace-write / full-access；workspace-write 只放开
  `shell.sandbox.writable.extra`（含工作区、`~/.m2` 等构建缓存、临时目录）的写权限，默认禁网，
  仅 `shell.sandbox.network.tools`（git/mvn）豁免联网。其它平台降级为 `DirectSandbox`（靠权限层兜底）。

### 关键坑：macOS 符号链接导致误判越界

macOS `/tmp`→`/private/tmp`、`/var/folders`→`/private/var/folders` 是符号链接，而 Seatbelt `subpath`
与路径包含判断都按**规范化真实路径**匹配，故 `SandboxPolicy.real()` / `PathGuard.canonical()` 必须
`toRealPath()` 解析符号链接，否则这些目录会被误判越界（表现为 mvn 写 jansi 锁文件 Operation not permitted）。

推论：**读校验不能用 `writableRoots` 当边界**（它含 /tmp 等，临时目录当工作区时会被穿越），
文件工具默认边界是 `SandboxPolicy.workspaceRoot`。

### 沙箱策略是配置驱动生成的

Seatbelt 的 `.sbpl` 策略文本由 `SeatbeltSandbox` 按 `SandboxPolicy` 渲染，不是硬编码常量；
加一档写权限请往 `shell.sandbox.writable.extra` 里配，不要改模板去放宽整条规则。

## 模型调用重试（`com.szh.utils.RetryExecutor` + `RetryPolicy`）

- 两个 DeepSeek 模型都接入指数退避重试。非 200 抛 `ModelApiException` 携带状态码与 `Retry-After`；
  仅 429/408/5xx 与网络 IO 异常可重试，其余 4xx 立即失败。
- 请求带 `model.request.timeoutSeconds` 超时。
- **新增 Provider 务必复用 `RetryExecutor` 而非各写一套**。
- 例外：**SSE 流式路径不重试**（增量已回放无法收回），阻塞路径仍走 RetryExecutor。

## 循环熔断（`com.szh.agent.LoopGuard`，run 级实例）

- 检测「连续失败」（工具抛异常或返回错误结果达 `agent.guard.maxConsecutiveFailures`）与
  「重复调用」（同工具 + 同参数连续达 `agent.guard.maxRepeatCalls`），触发即结束 run 并回传原因。
- 分级护栏：区分「工具故障」与「命令业务失败」；硬熔断前先经 `decorateContext` 注入一条纠偏软提示，
  至少被送达过一轮才允许 trip，避免模型至死没见过提醒等于硬杀 run。被缓下的轮次里计数继续累加。
- 两条运行时都接入：`AgentRuntime` 内联、`AgentResponseRuntime` 经 `HandleContext` 传给 `FunctionCallHandler`。
- **工具执行必须 try-catch 兜底**，异常转错误结果计入熔断，不再让整个 run 崩溃。
- `LoopGuard.record` 已 `synchronized`（并行工具执行会多线程回调）。

## 与验证闭环的复用关系

验证命令执行链路完全复用既有安全设施：先 `ShellSecurity.authorize`（被拒即记为失败并回灌原因），
再 `ShellSecurity.wrap`（OS 级沙箱），最后交 `ShellRunner`。详见 `verify-gate.md`。
