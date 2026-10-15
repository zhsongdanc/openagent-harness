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

## 冒烟

`com.szh.test.InstructionMemorySmokeTest`（main 直跑，临时目录搭目录链 + Fake workspace，不依赖模型）覆盖：
分层顺序、总开关/项目层开关、排除子树命中与不命中、`memory-bound` 满足/不满足、front-matter 不被当正文、
`@path` 递归与循环保护、异常降级为 BASE、按角色跳过项目层、`/memory` 描述行非空。
