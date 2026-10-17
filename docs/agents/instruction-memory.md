# 指令记忆装配（L0/L1/L2 分层机制，L1 按需记忆）

> 命中条件：改动 `com.szh.context.InstructionMemoryLoader` / `AgentState` 的 prompt 装配、
> 调整 `memory.instruction.*` 配置、调整本仓库 `AGENTS.md` 或 `docs/agents/**` 的归属，
> 或遇到「AGENTS.md 改了没生效」「子 agent 的 system prompt 少了内容」「别的目录为什么会加载本项目记忆」时，
> **先读完本文再动手**。

## 为什么做成三层

常驻 system prompt 的每一条都按「每轮每次调用」付费，且会挤占压缩阈值预算、稀释模型对真正硬约束的注意力。
判据是：**每条常驻文字能否对应一次可预期的错误避免**。据此分层：

| 层 | 位置 | 装配方式 | 内容 |
| --- | --- | --- | --- |
| L0 常驻 | 仓库根 `AGENTS.md` | 每次会话启动即进 system prompt | 构建命令、几条「违反就坏」的铁律、L1 路由表 |
| L1 按需 | `docs/agents/*.md` | **不常驻**，模型按路由表 `read_file` 自取 | 各模块细节、踩坑根因、配置语义、冒烟清单 |
| L2 条件化 | 加载器闸门 | 按环境/角色决定装不装 | 项目层总开关、排除子树、仓库归属绑定、子 agent 按类型 |

> ⚠️ L1 文档**绝不能**用 `AGENTS.md` 的 `@path` 引入语法挂载：`readAndExpand()` 是加载时 **eager 展开**的，
> 拆文件再 import 回来一个 token 都省不了，等于白拆。L0 里只写「路径 + 命中条件」，让模型自己 `read_file`。

## 装配顺序（`loadSystemPrompt`）

```
BASE_SYSTEM_PROMPT（内置，含规划决策契约）
  → 全局层 ~/.openagent/AGENTS.md            [scope=global]
  → 目录链项目层 文件系统根 … → workspace     [scope=project，越靠近 workspace 越后应用]
  → rolePromptAppendix（仅子 agent 等带角色的 AgentState）
  → 运行期软装饰（PlanModePolicy / VerifyPolicy / LoopGuard / 长期记忆召回）
```

- 目录链由 `collectUpward()` 从 workspace 向上遍历到根，反转为「根在前」。这意味着**项目层天然只在
  该目录及其子目录内生效**：在别的工程里跑 openagent，加载的是那个工程自己的 `AGENTS.md`（如果有），
  本仓库这份不会被带过去。真全局共享的约定才写进 `~/.openagent/AGENTS.md`。
- 每段来源都带 `[scope=… | source=…]` 标记，便于事后从快照里辨认哪层出的问题。
- **软装饰一律追加在本段之后**（`VerifyPolicy`/`PlanModePolicy`/`LoopGuard.decorateContext` 都是往
  SystemMessageItem 尾部 append），故 L0 + 项目记忆构成**稳定前缀**，能被 DeepSeek 的 prompt cache 命中。
  新增装饰时务必保持「往后追加、不改写前部」，否则整段前缀 cache 作废，接下来一段时间全按未命中计费。
  同理，改动 `AGENTS.md` 正文会让前缀 cache 失效一次——攒着改比零敲碎打改划算。

## L2：项目层的四道闸门

按顺序判定，任一不通过则该层被跳过（记 info 日志，不报错）：

1. **总开关** `memory.instruction.enabled`（false 时连全局层都不装，只留 BASE prompt）。
2. **项目层开关** `memory.instruction.project.enabled`（false 时保留全局层、只去掉目录链项目层）；
   REPL `--no-project-memory` 即等价于把它设为 false。
3. **排除子树** `memory.instruction.project.exclude`（逗号分隔的目录名或相对路径前缀，
   默认 `eval,.agent-data,target`）：workspace 相对某个候选 `AGENTS.md` 所在目录的相对路径若以这些
   片段开头，说明这次是「仓库内的外部任务/评测工作区」而非在开发本项目，跳过该层。
   这条专门解决「在本仓库的 `eval/terminal-bench/**` 下跑任务却被灌进 1w token 框架 internals」。
4. **仓库归属绑定**：`AGENTS.md` 首块 YAML front-matter 里写 `memory-bound: marker1, marker2`，
   加载器要求该文件所在目录能解析到**至少一个** marker 文件，否则整层跳过。
   本仓库声明 `memory-bound: pom.xml,src/main/java/com/szh/agent/AgentState.java`——
   补上「目录链向上遍历可能因复制/软链/monorepo 布局而误捞」这个残余缺口，同时让文件自述归属。

`AgentState(eventStore, rolePromptAppendix, projectMemory=false)` 走的是第五条路：**按角色裁剪**。
`SubAgentType.EXPLORE` 用它，纯检索子 agent 不再背项目记忆。

## 观测

- REPL `/memory`：打印每一层的来源路径、字节数、是否被哪道闸门跳过及原因，末尾给总长度与估算 token
  （`长度/2.2` 的粗算，仅用于量级判断，不是权威值——权威值看 `TokenUsageEvent` 的 prompt/cached）。
- 加载器每次装配记一条 info：纳入/跳过的来源数、总长度、估算 token；异常一律降级为 BASE prompt，
  **绝不阻断 `AgentState` 构造**（这是既有不变量，别改成抛异常）。
- 事后核查「当时真正送进去的 system prompt」看上下文快照（`observability.md`），不要靠推断。

## 长期记忆（另一套系统，勿混淆）

`AGENTS.md` 属于 L3 指令记忆（静态、每次装配）；`LongTermMemory` + SQLite/FTS5 属于 L4 动态记忆
（`remember`/`recall`/`forget`，每轮 `injectRecall` 召回注入，反思抽取由 `memory.reflection.trigger`
控制，默认 `on_compaction`）。两者唯一的共同点是都往 system prompt 尾部追加，都要守「不破坏前缀 cache」这条。

### L4 写入的两条通道（第二阶段）

自动提取**不直接产正式记忆**，中间多了一层候选（`com.szh.memory`，候选/日志是派生表，
**不进事件流**——记忆提取是非确定性 LLM 调用，当事件会在回放时重复写入）：

- 自动通道（异步，后台单线程）：`MemoryExtractor.extract(sessionId, transcript, sourceType)` 产
  `MemoryCandidate`（带 `evidence` 提取依据 + `confidence` 自评可信度）→ 按 `fingerprint` 幂等落库 →
  `MemoryConsolidator.apply(allowLlmArbitration=true)` 整理落正式库。
  抢救与 run 末反思两条通道输入不相交但可能蒸出同一事实，靠指纹幂等收口（不再双写）。
- 显式通道（同步）：`RememberTool → LongTermMemory.remember()` 走 write-through——不入队、立即返回 id，
  但同样交给整理器（不开 LLM 仲裁，避免给请求路径加一次模型调用），并留下 `EXPLICIT` 候选记录便于追溯。

### 整理动作（取代第一阶段「相似即覆盖」）

相似度分两档（`memory.dedup.threshold`=0.72 为 mid 下界，`memory.dedup.highThreshold`=0.92 为近重复下界）：

| 动作 | 触发 |
| --- | --- |
| `SKIP` | 规范化正文完全相同，只指向既有 id |
| `UPDATE` | 同一事实的修正，复用旧 id，旧值归档整理日志 |
| `MERGE` | 互补细化，LLM 生成合并文本写回旧 id |
| `SUPERSEDE` | 冲突的新事实取代旧行：旧行 JSON 进日志后删除（**不给 FTS5 加状态列**，避免整表重建迁移） |
| `ADD` | 无可靠相似项，或 mid 档判定「相关的两件事」（异分类） |
| `DISCARD` | `confidence=low` 的自动候选（显式写入不受此限） |

mid 档（相关但未必同一事实）在自动通道走 LLM 仲裁（`memory.consolidation.llm.enabled`），输出非法则回退
启发式（同分类→UPDATE、异分类→ADD）。候选层 `memory.candidate.enabled=false` 或建表失败时整体降级：
不持久化候选、不写日志，但**语义整理能力保留**。

### L4 读取侧与画像/目标（第三阶段）

第二阶段解决「写进来的东西干不干净」，第三阶段解决「用起来像不像个人助手」——都发生在 `injectRecall` 这一条
路径上（两条运行时都传 `agentState.getModelContext()` 作近期上下文），组装成一个受**总预算**约束的注入块，
按 **画像 > 目标 > 召回** 优先级拼接（`LongTermMemory.buildMemoryContextBlock`，包私有，测试经公开 `injectRecall` 观测）：

- **核心画像**：新增 `MemoryCategory.PROFILE`（+ `from()` 认「画像/档案」别名），约定 `scope=GLOBAL` 跨项目可见。
  由 `store.listByCategory(PROFILE, GLOBAL, …)` 常驻聚合成一小段文本，**每轮必注入、不依赖关键词召回**——
  「用户是谁、长期偏好」这类低频高价值事实不该等模型问起才想起。**复用 `memory_fts` 而非新表/独立文档**：
  画像就是普通记忆，自动继承第二阶段的整理/幂等/降级链路，零迁移成本。
- **活动目标**：独立 `memory_goals` 普通表（`SqliteGoalStore`，**非 FTS5**，避开整表重建迁移），带状态机
  `ACTIVE→DONE/CANCELLED/SUPERSEDED`（终态补 `completedAt`）。进行中的目标同样常驻注入，让助手知道「手上还有什么没做完」。
  目标是**待办而非知识**，故走 `GoalTool`（`goal`，action 分派）而非 `remember`；目标层不可用（`memory.goals.enabled=false`
  或建表失败）时 `ToolRegistry` 直接不注册该工具，`LongTermMemory` 各 goal 门面返回 false/null 静默降级。
- **读取侧三优化**（都不增加模型调用）：① 短输入查询扩展（本轮输入 <`memory.retrieval.query.expandMinLen`=12 且有近期对话时，
  用尾部非 system 消息补全检索词，缓解「那为什么这样设计？」这类指代追问召回落空）；② 相关性下限过滤
  （`memory.retrieval.minScore`，默认 0 关闭）；③ 单条正文截断（`memory.retrieval.perItemMaxChars`=600）。整个注入块封顶
  `memory.retrieval.inject.maxChars`=6000，超预算的段落截断加「…」。**默认值一律保持既有召回行为不变**。
- **可观测性 / 人工纠正**：REPL `/memory` 在 L3 装配明细之下追加 L4 段（总条数/画像/进行中目标/最近记忆/最近整理决策日志，
  全部读 `LongTermMemory` 的观测访问器，整块再包一层 try-catch，展示失败绝不拖垮 REPL）；`/memory forget <id>` 走 `forget(id)`。
  ⚠️ `forget`/`deleteGoal` 已改为**删前先查**、返回值表示「确实删掉了」——`ForgetTool`/`GoalTool`/`/memory forget` 都据此
  区分「已删除」与「本就不存在」，别再退回无条件返回 true。

## 冒烟

`com.szh.test.InstructionMemorySmokeTest`（main 直跑，临时目录搭目录链 + Fake workspace，不依赖模型）覆盖：
分层顺序、总开关/项目层开关、排除子树命中与不命中、`memory-bound` 满足/不满足、front-matter 不被当正文、
`@path` 递归与循环保护、异常降级为 BASE、按角色跳过项目层、`/memory` 描述行非空。
