# 元工具：agent 自组织（L1 按需记忆）

> 命中条件：改动 `com.szh.tool.tools.meta` / `TodoStore` / `AgentModeStore` / `PlanModePolicy` /
> `MetaToolSupport`，或遇到「PLAN 模式下还能写文件」「切模式没生效」「exit_plan_mode 卡住」时，
> **先读完本文再动手**。

## 定位（总开关 `meta.tools.enabled`）

对标 Claude Code 的 TodoWrite / AskUserQuestion / SwitchMode / ExitPlanMode，给 agent「自组织」能力——
自己维护多步任务清单、结构化问询用户、在 NORMAL/PLAN 模式间切换，而不是只能一路 tool_call 到底。
四件套：`todo_write` / `ask_user_question` / `switch_mode` / `exit_plan_mode`，均继承 `MetaToolSupport`
（统一入参解析 + `emit` 落事件 + `inlineResult=true` 回执内联不落盘）。

## 自主规划（对标主流「按复杂度自动决定是否先规划」）

不用独立分类器，而是把判断交给模型——`InstructionMemoryLoader.BASE_SYSTEM_PROMPT` 内置一段「规划决策契约」：
简单/单点/低风险任务直接做，复杂/多步/跨多文件/歧义/破坏性或大范围重构任务先 `switch_mode(plan)`
探索出方案（可配 `todo_write` 拆解），方案完备后 `exit_plan_mode` 提交审批。
契约是软约束，AGENTS.md 可细化覆盖。

## 状态双写（关键约定）

元工具改变的是 agent 的自组织状态而非外部世界。运行期待办/模式态各用一个 **session 键的进程内单例**
（`TodoStore` / `AgentModeStore`，对齐 `LongTermMemory.get()` / `McpClientManager.get()` 范式）
供 REPL 低延迟渲染与跨轮复用；同时把变更落成 **surface 事件**（`TodoUpdatedEvent` / `ModeSwitchedEvent`）
进事件日志（唯一真相源），供 `/session` 恢复回灌与 TraceReplay 回放。

**surface 事件不进模型上下文**：两个新事件刻意**不继承 `MessageEvent`**——清单/模式内容已通过工具结果
回执给了模型，再塞进 `modelContext` 既冗余又会破坏 DeepSeek Responses API 的 function_call 分组约束。
故 `AgentState.resume()`/`deriveMessages()` 天然忽略它们，只由 REPL/回放消费。

## 工具如何拿到落库入口

工具只拿得到 `ToolContext`，故约定运行时在 `ParallelToolExecutor.runSingle` 构造 `ToolContext` 时注入
`AgentState` + `turnId` + `round`（新增字段），元工具经 `MetaToolSupport.emit(ctx, event)` 落库；
脱离运行时的单测直调时 `AgentState` 为 null，`emit` 静默跳过、内存 store 仍更新，保证工具可独立测试。

## PLAN 模式（`switch_mode`）

会话级模式，两条运行时（`AgentRuntime`/`AgentResponseRuntime`）**每轮开头**读 `AgentModeStore`，
经 `PlanModePolicy` 施加软硬双约束：

1. `effectiveTools` 用**拒绝名单**（`write_file`/`edit_file`/`delete_file`/`git_add`/`git_commit`/
   `git_push`/`mvn`/`remember`/`forget`/`dispatch_subagent`）剔除写入类工具，只留只读检索 + 元工具；
2. `decorateContext` 往上下文副本的 system prompt 追加规划契约（只改副本不动真相源）。

切换**下一轮生效**，无需重启会话。用拒绝名单而非白名单：默认放行、仅剔已知写入工具，新增只读工具
无需改这里，底层仍有权限闸门 + 沙箱兜底。用户也可在 REPL 用 `/mode plan|normal` 切换
（写同一份 store + 落同一事件）。

## exit_plan_mode（规划退出审批闸门，对标 Claude Code ExitPlanMode）

PLAN 下模型方案完备后调它，把方案正文连同「是否开始执行」抛给用户审批（复用 `ConsoleQuestionPrompter`，
两个互斥选项）。**仅当用户明确选「批准并开始执行」**才 `AgentModeStore.set(NORMAL)` 并落
`ModeSwitchedEvent`（自动切回，无需用户手动 `/mode normal`）；选「继续修改」或自定义反馈一律留在 PLAN、
把反馈回灌模型完善。非交互（EOF，`--prompt`/单测）安全兜底：不擅自切 NORMAL，提示模型把方案作为
最终答复输出供审阅。它是元工具、非写入类，故**不在** `PlanModePolicy.MUTATING_CODES`，
PLAN 下天然保留（否则无法提交审批退出）。

## ask_user_question

结构化多选/单选问询，交互复用 `ConsoleQuestionPrompter` 读 stdin（沿用 `ConsolePermissionPrompter` 约定：
提示走 stderr、`synchronized` 防并行串台、run 期间 REPL 不并发读）；非交互（EOF，如 `--prompt`/单测）
返回 `null`，工具据此回「无人可答」提示引导模型自行决策而非死等。不落事件（即时交互，答案经工具结果回传即可）。

## REPL 渲染

`/todo` 查看当前清单，每轮对话结束后自动渲染待办（若有）；`/mode [plan|normal]` 查看/切换模式，
banner 显示当前模式；`/session <id>` 恢复时 `restoreMetaState()` 扫事件流回灌
`TodoStore`/`AgentModeStore`。TraceReplay 文本时间线与 HTML 均含 TODO/MODE 事件渲染（各自着色）。

## 冒烟

`com.szh.test.MetaSmokeTest`（main 直跑，注入 Fake 问询器 + 进程内事件存储，不依赖真实 API/stdin）
43 项断言全过：todo 替换/merge/状态归一化/渲染/事件落库、事件编解码往返、问询解析/结构化回执/
非交互兜底、模式切换/PLAN 工具过滤、exit_plan_mode 批准切回 NORMAL 落事件/拒绝留 PLAN/非交互兜底、
registry 装配。
