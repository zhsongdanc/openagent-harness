# 子 agent / Task 派生（P3，L1 按需记忆）

> 命中条件：改动 `com.szh.agent.subagent` / `com.szh.tool.tools.subagent` / `SubAgentType`，
> 或遇到「子 agent 事件混进父上下文」「子 agent 拿不到某个工具」「派生后上下文没省」时，
> **先读完本文再动手**。

## 定位（总开关 `subagent.enabled`）

对标 Claude Code 的 Task 工具，把「会产生大量中间产物、但主对话只需要一个结论」的子任务外包给
独立上下文的子 agent，主 agent 只拿最终结论——这是长任务不炸上下文的核心手段，与既有的上下文压缩
（L0→L1）、工具结果落盘互补（压缩有损、隔离无损）。

## 触发

主 agent 调 `dispatch_subagent(type, prompt)` 工具（`com.szh.tool.tools.subagent.DispatchSubAgentTool`，
注册进 `ToolRegistry`）。`prompt` 必填且**必须自包含**——子 agent 看不到主对话历史。
`type` 见 `SubAgentType`，每种类型绑定一份「角色指令 + 工具白名单」；未知/不传回退 general-purpose。

| type | 工具白名单 | 是否携带项目指令记忆（`memory-bound` 层） |
| --- | --- | --- |
| `general-purpose` | 全部工具 | 携带 |
| `code-review` | 只读子集（read_file/repo_map/grep/git 只读等） | 携带（审查要对照项目约定） |
| `explore` | 只读检索子集 | **不携带**，只保留基础 prompt + 角色指令 |

`explore` 只做定位、不需要知道本项目的沙箱/事件/压缩约定，故 `SubAgentType.carryProjectMemory()`
对它返回 false，经 `AgentState(eventStore, rolePrompt, projectMemory=false)` 跳过项目层装配——
这是「按需记忆分层」在派生场景的落点：**并行派生多个检索型子 agent 时，成本从 N×项目记忆降为 0**。
新增类型时显式决定这个开关，别默认全带。

## 执行（`SubAgentExecutor.dispatch`）

为每次派生建**全新独立运行时环境**——独立 `EventStore` + 独立 `AgentState` + 独立派生 `sessionId`
（`{parent}-sub-{8位短id}`）+ 按类型白名单构造的 `ToolRegistry`；复用现有两条运行时
（`subagent.runtime`=chat/response，Responses 不可用自动回退 chat），子 agent 用更小的
`subagent.maxRound` 收紧轮次。

角色指令经 `AgentState(EventStore, rolePromptAppendix)` 追加在分层 system prompt 之后
（携带项目记忆时顺序是：基础 prompt → 全局记忆 → 项目记忆 → 角色指令）。

## 关键约束（务必遵守）

- **子 agent 事件绝不混入父事件流**——父 `AgentState.resume()` 从事件流重建 modelContext，
  若子事件混进去，断点恢复会把子 agent 全部中间产物灌回主上下文，直接违背「只拿最终结论」。
  子 agent 用自己的 sessionId 独立落库，父只通过工具结果拿结论；`TraceReplay` 天然按 session
  分别回放父子时间线。
- **递归深度靠结构杜绝**：子 registry 以 `childDepth=parentDepth+1` 构造，
  `ToolRegistry.subAgentTools()` 在 `subAgentDepth >= subagent.maxDepth`（默认 1）时**不注册**
  `dispatch_subagent`，子 agent 默认拿不到派生工具，无法再生孙 agent；`SubAgentExecutor` 另有
  `childDepth > maxDepth` 兜底拒绝。
- **工具白名单是唯一真实缺口**：`ToolRegistry(Set<String> allowedCodes, int subAgentDepth)` 构造，
  rebuild 末尾按 `allowedCodes` 过滤（null=全量）。

## 已知取舍

- 子 agent 继承全局 `model.stream.enabled` 与权限确认策略；并行派生多个含 shell 的子 agent 时，
  `ConsolePermissionPrompter` 读 stdin 可能串台，建议模型顺序派生或依赖 `shell.permission.autoApprove` 兜底。
- 结论超长仍走既有落盘（`inlineResult` 默认 false）。
- 上下文快照侧车按 sessionId 分目录，子 agent 天然隔离。

## 冒烟

`com.szh.test.SubAgentSmokeTest`（main 直跑，注入 Fake 模型不依赖真实 API）覆盖类型解析 /
白名单过滤 / 深度闸门 / 事件隔离 / 端到端派生返回结论。
