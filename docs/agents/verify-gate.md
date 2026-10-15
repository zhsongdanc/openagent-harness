# 验证闭环 VerifyGate（L1 按需记忆）

> 命中条件：改动 `com.szh.agent.verify` 下任何代码、调整 `agent.verify.*` 配置、
> 或遇到「验证没跑」「验证把 run 卡住」「失败没回灌给模型」时，**先读完本文再动手**。

## 定位

对标 Aider `--auto-test`、Claude Code `Stop` hook、SWE-agent finish gate，给本地编码助手补上
「能不能自己确认改对了」的核心能力——拦截「模型宣布完成」这一刻，跑项目声明的验证命令（编译/测试/lint），
不通过就把失败输出结构化塞回上下文强制继续修，通过才真正收尾。把「靠 prompt 求模型记得跑测试」
升级为**框架级硬闸门**。总开关 `agent.verify.enabled`（代码默认关闭；本仓库 properties 已置 true，
但只要没配 verify.json 就还是「未武装」）。

## 触发条件

两条运行时（`AgentRuntime` FINAL_ANSWER 分支 / `AgentResponseRuntime` `!anyToolCall` 分支）
各在 run 开始建一个 run 级 `VerifyGate` 实例，每次执行工具调用后 `markDirty(toolCode)`——
命中写入类工具（`write_file`/`edit_file`/`delete_file`/`git_add`/`git_commit`/`git_push`）即置脏。

收尾分支调 `shouldVerify()`：闸门**武装**（`enabled` 且 `commands` 非空）且
（未开 `onlyWhenDirty` 或本 run 确实改过代码）才验证，纯问答/只读任务自动跳过、不做无谓编译。

> 新增写入类工具时，务必同步加进脏标记清单，否则改了代码却不触发验证。

## 配置来源与优先级

`VerifyConfigLoader.load(workspace)` 分层装配：

1. 基线从 `application.properties` 读调优旋钮（`agent.verify.enabled/maxFixRounds/timeoutSeconds/onlyWhenDirty`）；
2. 项目级 `{workspace}/.openagent/verify.json` **优先于**全局 `agent.verify.config.file`
   （默认 `~/.openagent/verify.json`）覆盖之，**验证命令清单只能由 json 提供**。

文件缺失或解析失败一律静默降级为「未启用」，绝不拖垮 run；命令串复用 `CommonUtils.expand`
做 `~`/`${VAR}` 展开（与 `McpConfigLoader` 同一套语义）。

```json
{"enabled":true,"maxFixRounds":3,"timeoutSeconds":600,"onlyWhenDirty":true,
 "commands":["mvn -q -DskipTests compile","mvn -q test"]}
```

## 硬闸门语义

- 命令按顺序执行、**fail-fast**（首个失败即停）。
- 未通过且 `canRetry()`（`verifyRounds < maxFixRounds`）时，`incrementRound()` 后把
  `VerifyResult.feedback(round, max)`（含失败命令/退出码/尾部输出 + 「验证通过前不要再次宣布完成」约束）
  作为一条 `UserMessageEvent` 回灌上下文并 `continue`，模型下一轮看到失败继续修；
- 超上限则返回「验证未通过（已尝试 N 次修复）」说明后 `break`
  （而非静默成功，让用户明确知道未通过）；通过则原样放行收尾。
- 失败输出按 `tool.result.max.chars` 做**尾部**截断（编译/测试报错通常在末尾）。

## 防死循环三重保障

`maxFixRounds`（验证-修复轮次上限）+ `agent.maxRound`（run 总轮次）+ `LoopGuard`（连续失败/重复调用熔断）
互为兜底。

## 复用而非各写一套

验证命令执行链路完全复用既有安全设施——先过 `ShellSecurity.authorize`（权限闸门，被拒即记为该条失败并
回灌原因），再 `ShellSecurity.wrap`（OS 级沙箱），最后交 `ShellRunner`（从 `ShellCommandTool.execute()`
抽取的「ProcessBuilder + redirectErrorStream + 独立读线程 + waitFor 超时 + destroyForcibly」静态入口，
`ShellCommandTool` 与 `Verifier` 共用，避免两套子进程逻辑）；命令串按空白拆成 argv，不经 shell 解释器，
无注入风险（**不支持管道/重定向**）。

## 软提示与可观测

- `VerifyPolicy.decorateContext` 仿 `PlanModePolicy` 只改上下文副本不动真相源，仅当闸门武装时
  往 system prompt 追加「完成前主动跑验证」契约，提升一次通过率。
- surface 事件 `VerificationEvent`（`EventEnum.VERIFICATION`，**不继承 `MessageEvent`**、不进 `modelContext`，
  对齐 `TodoUpdatedEvent`/`ModeSwitchedEvent`），记录每次验证的命令/退出码/通过与否，落事件日志供
  `TraceReplay` 回放（文本时间线 `[VERIFY ✓/✗]`、HTML 按 `VERIFY` 类着色）。
- REPL：`/verify` 即时对当前 workspace 跑一遍验证并打印结果（复用 `Verifier`，不依赖模型）；
  `printModel()`/banner 增补 `verify=on(N 命令)/off` 让用户一眼看到闸门是否武装。

## 冒烟

`com.szh.test.VerifySmokeTest`（main 直跑，关闭 `shell.security.enabled` 用 `true`/`false` 零副作用命令，
不依赖真实模型/构建）42 项断言全过：武装判定 / fail-fast / 失败回灌文本 / onlyWhenDirty 脏标记 /
maxFixRounds 计数边界 / verify.json 分层加载与 `~`/`${VAR}` 展开 / 缺文件降级。
