# 事件溯源与上下文机制（L1 按需记忆）

> 命中条件：改动 `com.szh.event` / `com.szh.store` / `com.szh.agent.AgentState` /
> `com.szh.context.compaction` / `com.szh.tool.result` 下任何代码，或遇到
> 「断点恢复后上下文不对」「压缩把东西压丢了」「Responses API 报 400」时，**先读完本文再动手**。

## 事件日志是唯一真相源

- 所有状态变更先 `applyEvent` 落库，运行期再增量维护 `modelContext`；断点恢复由 `AgentState.resume()`
  从事件流重建，不要在别处旁路维护状态。
- `AgentState.applyEvent` 里「落库 + 追加 context」必须保持原子：并行工具执行时多个 worker 线程会同时回调，
  故方法已 `synchronized`，新增类似状态维护入口时不要绕开它。
- 事件日志按前缀截断是允许的（进程崩溃 / 写库失败），但半截 step 会让重建出的 history 以未应答的
  `tool_calls` 结尾、模型侧直接报错，故 `trimIncompleteTail()` 会从尾部裁掉未闭合的一步。

## 两套运行时（改动需兼顾两者）

| 链路 | 运行时 / 执行器 | 协议 |
| --- | --- | --- |
| Chat | `AgentRuntime` / `AgentExecutor` | Chat Completions API（`Model`） |
| Response | `AgentResponseRuntime` / `AgentResponseExecutor` | Responses API（`ResponseModel`） |

- **DeepSeek Responses API 分组约束**：同一轮内所有 `function_call` 必须连续排列、
  `function_call_output` 统一跟在其后，交错会触发 400。这也是并行工具执行器必须
  「全部 Started → 并发执行 → 全部 Finished（按调用原序）」落库的原因。
- 收尾判据不同：`AgentRuntime` 看 FINAL_ANSWER 分支，`AgentResponseRuntime` 看 `!anyToolCall` 分支。
  任何 run 级闸门（VerifyGate / LoopGuard / Trace 快照）都要在两处各接一次，漏一处就是行为不一致。

## 上下文压缩（单阈值 + 渐进升级）

- 单一阈值 `compaction.threshold`（占上下文窗口比例），超阈值先 L0 规则压缩、仍超再升级 L1 LLM 摘要。
- **压缩检查点持久化**：L1 摘要既花 LLM 调用又非确定性，故压缩发生时落一条 `ContextCompactedEvent`
  （`EventEnum.CONTEXT_COMPACTED`，**不继承 `MessageEvent`**、不进增量 `modelContext`，但被
  `AgentState.deriveMessages()` **特判消费**）把摘要正文与保留尾部条数固化进事件流；
  `resume()` 遇到该事件即把此前累积历史折叠为 `[system, 摘要, 尾部 keepRecentCount 条]`，
  直接重建到运行期压缩态、**无需再调 LLM 重压**（多次压缩按事件顺序增量叠加）。
- L0（`StepCompactor`）是确定性零成本规则压缩，**不落检查点**——恢复后重跑即得相同结果。
- `maybeCompact` 返回 `CompactionResult`（`unchanged` / `l0Only` / `summarized`）携带
  `isChanged` / `isSummarized` 与摘要，供两条运行时决定是否 `replaceModelContext` + 落检查点。
  判断是否变更一律用 `isChanged()`，不要用「比较消息条数」——L0 只截断正文不改条数，比较条数会漏替换。
- `collapseCompacted()` 折叠时 summary 为 null（异常兜底）原样返回上下文，不改状态。
- 与长期记忆联动：`compactionHappened` 标记是 `memory.reflection.trigger=on_compaction` 的判据，
  压缩丢弃的信息会沉淀进长期记忆，二者互补。

## 工具结果外存

- 超阈值的工具输出落盘到 `{workspace}/.agent-data/tool-results/{sessionId}/{callId}.txt`，
  上下文只保留引用存根，模型用 `read_tool_result` 分页读取。
- **`read_tool_result` 自身输出必须豁免落盘**（否则无限套娃：读存根 → 结果又超限 → 又落盘）。
- 落盘失败一律降级为内联返回，绝不抛异常拖垮 run（与 `TraceContextWriter` 同一套防御哲学）。

## surface 事件的统一约定（反复踩坑点）

新增「只给观测/恢复用、不该进模型上下文」的事件时（已有 `TodoUpdatedEvent` / `ModeSwitchedEvent` /
`VerificationEvent` / `TokenUsageEvent` / `ContextCompactedEvent`）：

1. **刻意不继承 `MessageEvent`**——内容已通过工具结果回执给了模型，再塞进 `modelContext` 既冗余
   又会破坏 DeepSeek Responses API 的 function_call 分组约束；
2. 因此 `AgentState.resume()` / `deriveMessages()` 天然忽略它们，只由 REPL / TraceReplay / Web Viewer 消费；
   例外只有 `ContextCompactedEvent`（被 `deriveMessages()` 特判消费）；
3. `EventJsonCodec.deserialize` 与 `TraceReplay` 的 describe / HTML 都是对 `EventEnum` 的**穷尽 switch**，
   加枚举值必须同时补齐这几处，否则编译直接报错（这是刻意保留的强制对齐，别改成 default 分支）。

## 存储引擎

由 `store.engine` 切换：`MEMORY`（进程内、非持久）、`MYSQL`（落库）、
`FILE`（Codex 风格 JSONL rollout，落 `~/.openagent/sessions/{sessionId}.jsonl`）。
三档都要能跑通 `resume()`，新增事件类型后逐档验证一遍。
