# Claude Code 与 opencode 的记忆系统对比

> 本文只做知识梳理，不涉及 openagent-harness 自身实现。
> 聚焦两点：① Claude Code 的 Auto Memory（user/feedback/project/reference 四类）默认加载什么、怎么加载（基于官方提示词与文档）；② opencode 的记忆机制（**基于实际拉取的 sst/opencode 源码逐文件分析**，见第二部分开头的源码清单）。
>
> opencode 源码已 clone 到 `/tmp/opencode-src`（commit `18ef3cc`, 分支 `dev`）以便核对。

---

## 一、Claude Code 的记忆系统

Claude Code 有**两套并行**的记忆机制，加载方式完全不同：

| 机制 | 谁来写 | 内容 | 默认加载方式 |
|------|--------|------|--------------|
| **CLAUDE.md** | 开发者手写 | 指令、规范、约束 | 每次会话**全量注入**（子目录的按访问目录时加载） |
| **Auto Memory** | Claude 自动积累 | 学到的经验、模式、用户纠正 | 每次会话只加载**索引 MEMORY.md**，正文按需召回 |

用户问的 `user / feedback / project / reference` 属于 **Auto Memory** 这一套。

### 1.1 Auto Memory 的四种 type

每条记忆是 `~/.claude/projects/<project-hash>/memory/` 下的**一个独立文件**（一条记忆 = 一个文件 = 一个事实），头部带 frontmatter：

```markdown
---
name: <short-kebab-case-slug>
description: <one-line summary — 召回时用于判断相关性>
metadata:
  type: user | feedback | project | reference
---

<正文；feedback / project 类还要跟 **Why:** 和 **How to apply:** 两行>
<用 [[other-memory-name]] 关联其它记忆>
```

四类语义：

- **user** —— 用户是谁（角色、专长、偏好）
- **feedback** —— 用户对"你该怎么工作"的指导，含纠正和已确认的做法，要写清原因（why）
- **project** —— 进行中的工作 / 目标 / 约束（代码和 git 历史里推不出来的），相对日期要转成绝对日期
- **reference** —— 外部资源指针（URL、看板、工单链接）

### 1.2 默认加载哪些、怎么加载（核心）

**关键结论：`type` 只是组织 / 召回用的元数据，不决定"是否默认加载"。四种类型待遇完全一样。**

1. **每次会话启动 → 只加载索引文件 `MEMORY.md`**
   - `MEMORY.md` 里每条记忆一行指针：`- [Title](file.md) — hook`，**不含 frontmatter、不含正文**。
   - 无论 user / feedback / project / reference，默认进上下文的都只是各自那一行摘要。
   - 索引有体量上限（约**前 200 行 / 25KB**），超出部分不进上下文。

2. **正文按需召回（lazy recall），不是默认加载**
   - 模型判断当前任务与某条记忆的 `description` 相关时，才用 Read 打开对应 `.md` 文件拿全文。
   - 召回的记忆被塞进 `<system-reminder>` 块，作为**背景上下文**（不是用户指令），且只代表"写入时"的状态——引用其中提到的文件/函数/参数前要重新核实是否还存在。

3. **写入侧约定**
   - 先查重：已有同类文件就更新，不新建重复；错的记忆直接删。
   - 不保存仓库已记录的东西（代码结构、历史修复、git 记录、CLAUDE.md）或只对本次对话有意义的内容。
   - 写完文件必须在 `MEMORY.md` 补一行索引。

### 1.3 CLAUDE.md 的四层层级（补充）

Auto Memory 之外，手写的 CLAUDE.md 是**全量加载**的，优先级从高到低：

1. 企业级 policy（最高，只读）
2. 用户级 `~/.claude/CLAUDE.md`（对所有项目生效）
3. 项目级 `./CLAUDE.md`（随 Git 提交、团队共享）
4. 子目录级 `src/CLAUDE.md` 等（只在处理对应目录文件时加载）

越具体的规则越优先，子目录会覆盖上层同类规则。

### 1.4 一句话总结

> user/project/feedback/reference 四类**都不会"整文件默认加载"**，默认进上下文的只有 `MEMORY.md` 里它们各自的一行索引；完整正文靠 `description` 相关性触发**按需读取**，并以 `<system-reminder>` 形式回灌。

---

## 二、opencode 的记忆系统（基于源码分析）

> 本节基于实际拉取的 sst/opencode 源码（`packages/opencode/src/session/instruction.ts`、`packages/core/src/fs-util.ts`、`packages/opencode/src/session/prompt.ts`、`packages/opencode/src/tool/read.ts`、`packages/core/src/config.ts`、`packages/core/src/global.ts`、`packages/web/src/content/docs/rules.mdx`），非二手资料。

### 2.0 最重要的结论：opencode 没有"自动记忆"

全代码库 grep `MEMORY.md` / memory 写入逻辑 = **零命中**（`memory` 关键字只出现在 xai 插件、`cli/heap.ts`（指 RAM）、`beast.txt` 提示词里，与记忆无关）。opencode 官方文档里这套东西叫 **Rules**，不叫 memory。

它的"记忆"本质是：**一组静态指令文件（AGENTS.md 等），在每次 LLM 请求时被读出来拼进 System Prompt**。没有 Claude Code 那种"模型自动写记忆文件 + 索引 + 按需召回"的机制。跨会话积累的动态记忆，opencode 本体不做，要靠外部 MCP / 插件。

整个逻辑集中在一个文件 `instruction.ts`，对外暴露 5 个方法：`systemPaths()`（算出该加载哪些文件路径）、`system()`（读文件拼 System Prompt）、`find(dir)`（找某目录的指令文件）、`resolve()`（读文件时动态附加子目录指令）、`clear()`（清 per-message 去重状态）。

### 2.1 两组候选文件名

源码里写死了两组文件名（`instruction.ts:60-68`）：

```ts
const globalFiles = [
  path.join(global.config, "AGENTS.md"),                 // ~/.config/opencode/AGENTS.md
  ...(!flags.disableClaudeCodePrompt
      ? [path.join(global.home, ".claude", "CLAUDE.md")] // ~/.claude/CLAUDE.md
      : []),
]
const instructionFiles = [
  "AGENTS.md",
  ...(!flags.disableClaudeCodePrompt ? ["CLAUDE.md"] : []),
  "CONTEXT.md", // deprecated
]
```

- `global.config` = XDG 配置目录 + `opencode`，即 `~/.config/opencode`（可被 `OPENCODE_CONFIG_DIR` 覆盖，见 `global.ts`）。
- `instructionFiles` 里还有个 **`CONTEXT.md`（已废弃）** 作为兜底，这点 web 资料普遍没提。

### 2.2 systemPaths()：启动时算出"常驻 System Prompt"的文件集

`systemPaths()`（`instruction.ts:110-153`）产出一个路径 Set，规则如下：

1. **全局层——只取第一个存在的文件**
   ```ts
   for (const file of globalFiles) {
     if (yield* fs.existsSafe(file)) { paths.add(...); break }  // 注意 break
   }
   ```
   `~/.config/opencode/AGENTS.md` 和 `~/.claude/CLAUDE.md` 里**只有一个会生效**（前者优先），不会叠加。

2. **项目层——第一个"文件名类型"命中即停，但该类型的所有祖先层级全叠加**
   ```ts
   for (const file of instructionFiles) {           // AGENTS.md → CLAUDE.md → CONTEXT.md
     const matches = yield* fs.findUp(file, ctx.directory, ctx.worktree)
     if (matches.length > 0) {
       matches.forEach((item) => paths.add(...))     // 所有祖先层的同名文件都加进来
       break                                         // 但不再看下一种文件名
     }
   }
   ```
   源码注释原话：*"The first project-level match wins so we don't stack AGENTS.md/CLAUDE.md from every ancestor."*
   - 精确含义：**AGENTS.md 优先于 CLAUDE.md/CONTEXT.md**——只要任意祖先目录有 AGENTS.md，就完全不看 CLAUDE.md。
   - 但 `findUp` 会从 `cwd` 一路向上收集到 `worktree` 根，**每一层的 AGENTS.md 都会被叠加**（monorepo 里子包 + 根的 AGENTS.md 同时生效）。
   - `findUp`（`fs-util.ts:154-166`）：从 `start` 逐级 `dirname` 上溯到 `stop`（含 stop），每层 `exists(join(current, target))` 就 push，返回**全部**匹配。

3. **config.instructions——额外补充文件，支持 GLOB / 绝对路径 / `~/` / URL**
   ```ts
   if (config.instructions) for (const raw of config.instructions) {
     if (raw.startsWith("http")) continue            // URL 在这里跳过，交给 system() 去 fetch
     const instruction = raw.startsWith("~/") ? join(home, raw.slice(2)) : raw
     const matches = path.isAbsolute(instruction)
       ? fs.glob(basename(instruction), { cwd: dirname(instruction), absolute: true })
       : relative(instruction)                        // 相对路径 → globUp
     matches.forEach((item) => paths.add(...))
   }
   ```
   - 相对路径走 `relative()` → `fs.globUp(pattern, cwd, worktree)`：在**每一层祖先目录**做 glob 匹配并累加（`fs-util.ts:184-198`，`dot:true`、只匹配文件）。所以 `"packages/*/AGENTS.md"`、`".cursor/rules/*.md"` 这类 GLOB 会在上溯路径上逐层展开。
   - `config.instructions` 的 schema 是 `Schema.String[]`（`core/src/config.ts:96`，注解 *"Additional paths or URLs supplying ambient instructions"*）；多份配置合并时数组做并集去重（`config.ts:48-49`）。
   - 受 `OPENCODE_DISABLE_PROJECT_CONFIG` 影响：置位时项目层与相对 glob 都改为只在 `global.config` 目录内找。

### 2.3 system()：每一步 LLM 调用都重新读盘注入

`system()`（`instruction.ts:155-169`）在 agent loop 的**每个 step** 都被调用（`prompt.ts:1260`）：

```ts
const files  = yield* Effect.forEach(Array.from(paths), read,  { concurrency: 8 }) // 本地文件
const remote = yield* Effect.forEach(urls, fetch, { concurrency: 4 })              // instructions 里的 URL
return [
  ...paths.flatMap((item,i) => files[i]  ? [`Instructions from: ${item}\n${files[i]}`]  : []),
  ...urls .flatMap((item,i) => remote[i] ? [`Instructions from: ${item}\n${remote[i]}`] : []),
]
```

- 结果被拼进最终的 system 数组：`system = [...env, ...instructions, ...mcpInstructions, ...skills]`（`prompt.ts:1264-1269`）。
- **每步都重新 `read` 一遍**——所以你改了 AGENTS.md，下一步就生效，无需重启。
- URL 指令用 `fetch()` 拉取，**5 秒超时**，失败静默返回空串（`instruction.ts:95-103`）。
- 每份内容都带 `Instructions from: <路径>` 前缀，让模型知道来源。

### 2.4 resolve()：读文件时"就近"附加子目录指令（真正的动态部分）

这是 opencode 里唯一带"按需/动态"味道的机制，由 **read 工具**触发（`read.ts:300`），不是启动时加载：

```ts
resolve(messages, filepath, messageID):  // instruction.ts:179-221
  sys    = systemPaths()                 // 已在 System Prompt 里的，跳过
  already= extract(messages)             // 本会话此前 read 已附加过的，跳过
  root   = InstanceState.directory
  current= dirname(filepath)
  while (current 在 root 之内 && current != root) {   // 从被读文件目录向上走到项目根
    found = find(current)                // 该目录第一个存在的 AGENTS.md/CLAUDE.md/CONTEXT.md
    if (!found || found==target || sys.has(found) || already.has(found)) { 上移; continue }
    if (claims[messageID].has(found))    { 上移; continue }   // 同一条消息内只附加一次
    claims[messageID].add(found)
    results.push({ filepath: found, content: `Instructions from: ${found}\n${read(found)}` })
    上移
  }
```

附加方式很关键——**不是塞进 System Prompt，而是拼进 read 工具的输出里，用 `<system-reminder>` 包裹**（`read.ts:355-356`）：

```ts
if (loaded.length > 0)
  output += `\n\n<system-reminder>\n${loaded.map(i => i.content).join("\n\n")}\n</system-reminder>`
```

同时把这些文件路径记进 read 结果的 `metadata.loaded`（`read.ts:365`）。

**三重去重**保证同一份子目录指令不会反复灌：

- `sys`（systemPaths）——已经常驻 System Prompt 的不再附加；
- `already` = `extract(messages)`——扫描本会话所有**已完成的 read 工具**的 `metadata.loaded` 路径（跳过被 compact 的），之前附加过的就不再附加（`instruction.ts:17-32`）；
- `claims: Map<MessageID, Set<string>>`——**同一条 assistant 消息内**每个文件只附加一次；消息结束时经 finalizer 调 `clear(messageID)` 清掉（`prompt.ts:691`、`1331`）。

> 效果：只有当你真的去 read 某个子目录下的文件时，那个子目录的 AGENTS.md 才会"就近"以 `<system-reminder>` 的形式补进上下文——这与 Claude Code 召回记忆用 `<system-reminder>` 承载的手法一致，但触发条件是"读了该目录的文件"而非"语义相关性"。

### 2.5 Claude Code 兼容开关

来自 `runtime-flags.ts:23-29`：

```bash
export OPENCODE_DISABLE_CLAUDE_CODE=1         # 关掉全部 .claude 支持
export OPENCODE_DISABLE_CLAUDE_CODE_PROMPT=1  # 只关 ~/.claude/CLAUDE.md 与项目 CLAUDE.md
export OPENCODE_DISABLE_CLAUDE_CODE_SKILLS=1  # 只关 .claude/skills
```

关闭后 `globalFiles` / `instructionFiles` 里的 CLAUDE.md 项直接从数组中剔除。

### 2.6 `/init` 命令

`/init`（`command/index.ts:72` "guided AGENTS.md setup"，模板 `command/template/initialize.txt`）会扫描仓库、必要时问几个问题，然后**创建或就地改进** AGENTS.md，聚焦"未来会话最可能需要的硬信息"：build/lint/test 命令、命令顺序、非显而易见的架构与目录结构、项目特有约定/坑、以及对 Cursor/Copilot 既有规则的引用。已存在则增量改进而非盲目重写。

### 2.7 动态长期记忆怎么办

opencode 本体不提供"模型自动写记忆文件"的能力（2.0 已从源码确认）。要做跨会话积累的偏好/项目事实，只能靠**外挂**，有两条载体（MCP 只是其中一种，非唯一）：

1. **MCP server** —— opencode 有一等公民的 MCP 客户端支持（`packages/core/src/config/mcp.ts`：`local`(command) / `remote`(url) 两种），在 `opencode.json` 的 `mcp` 字段配置，接一个 memory MCP 即可。
2. **生态插件** —— 官方生态文档 `packages/web/src/content/docs/ecosystem.mdx:42` 明确把跨会话记忆列为插件：`opencode-supermemory —— Persistent memory across sessions using Supermemory`。

> 另一个易误判的细节：`packages/opencode/src/session/prompt/beast.txt:113-123` 有一段 "# Memory"，告诉模型"你有一个记忆文件 `.github/instructions/memory.instruction.md`，用户让你记就更新它"。但这**只是某个可选提示词的约定**（默认提示词 `default.txt` 没有），本质是"让模型用普通 read/write 工具自己读写一个 md 文件"——没有记忆子系统、没有索引、没有自动抽取/召回，且那个文件也不会被自动加载（不属于 AGENTS.md/CLAUDE.md/CONTEXT.md，除非在 `instructions` 里注册）。够不上 Claude Code 意义上的 auto-memory。

### 2.8 插件如何管理记忆：opencode 给钩子，插件全权负责（源码级）

核心结论：**opencode 完全不控制记忆的写入/更新/遗忘——它只提供"触发时机 + 通道"，记忆策略 100% 由插件自己实现。**

#### opencode 侧：只有通用钩子，没有"记忆"概念

`packages/plugin/src/index.ts` 的 `Hooks` 接口和 `PluginInput` 给插件的全是通用生命周期钩子，没有一处是记忆 API：

| opencode 提供的钩子 | 插件能拿来干嘛（记忆视角） |
|---|---|
| `event`（总线事件：会话生命周期、message 更新、idle…） | 监听"某轮结束/会话空闲" → 触发写入 |
| `chat.message`（收到新用户消息） | 在 prompt 里注入召回的记忆 |
| `tool: { [key]: ToolDefinition }` | **注册自己的工具**（如 `remember`/`recall`/`supermemory`），交给模型调用 |
| `tool.execute.before/after` | 拦截工具调用做记录 |
| `experimental.chat.system.transform` / `messages.transform` | 把召回内容塞进 system prompt / 消息 |
| `experimental.session.compacting` | 压缩时注入/沉淀记忆 |

`PluginInput` 还给了插件 `client`（完整 opencode SDK 客户端）、`directory`、`worktree`、`$`(Bun shell)——插件可反向读会话数据、自己落库。opencode 源码里**根本没有** write/update/forget memory 这类接口或策略；它只负责"在什么时机 fire 哪个钩子"和"提供工具注册 + 上下文注入的通道"。

#### 插件侧：整个"记忆大脑"都在插件里（以 opencode-supermemory 为例）

- **自动写入（插件自主决定，非 opencode 也非模型）**：`captureEveryNTurns` —— 每 N 个完成轮次保存一批，会话结束/opencode 关闭时 flush 余量；用**稳定 capture ID 做幂等去重**；`<private>` 内容自动脱敏。挂在 `event`（轮次完成/会话生命周期）钩子上由插件自己算。
- **关键词触发**：用户说 "remember / save this / don't forget" 或自定义 `keywordPatterns` → 插件提示 agent 存记忆。
- **召回三种模式，全由插件配置决定**：
  - `recallMode: "direct"` —— 插件**自动**语义检索并注入（挂 `chat.message`/`system.transform`），模型不参与决策；
  - `recallMode: "advisory"` —— 插件每轮给模型一段 directive，让**模型自己决定**是否调 `supermemory` 工具召回；
  - `"off"` —— 关闭。
- **抢占式压缩**：80% 上下文时借 `experimental.session.compacting` 触发 opencode 摘要、注入项目记忆、并把摘要本身存成一条记忆。
- 另注册一个 `supermemory` **工具**供模型直接调用。

#### 职责边界

```
opencode  =  只控制"钩子何时触发" + 提供"工具注册 / 上下文注入 / SDK 客户端"通道
             （对"记忆"零认知、零策略）
插件      =  完全自主实现记忆策略：存什么 / 何时存(captureEveryNTurns) /
             去重(幂等 ID) / 更新 / 脱敏 / 召回模式 / 遗忘
模型(LLM) =  仅当插件选择 tool / advisory 路线时，才由模型决定"要不要调 remember/recall"
             ——但这仍是插件的设计选择，不是 opencode 的
```

所以**不是**"opencode 决定何时写、插件执行"，而是：**opencode 发出一串通用生命周期事件并开放工具/上下文钩子；插件订阅这些时机，自己实现整套写入-更新-召回-遗忘逻辑。** 至于是"自动写"还是"让模型决定写"，取决于插件选哪种模式（supermemory 两种都支持，可配）。

### 2.9 案例剖析：opencode-supermemory 是怎么做记忆的（通俗版）

> 以官方生态里最典型的记忆插件 `opencode-supermemory` 为例，源码逐文件读过（`client.ts`/`capture.ts`/`recall.ts`/`index.ts`/`tags.ts`/`config.ts`/`privacy.ts`）。下面用大白话讲清四件事：**存在哪、什么时候读、什么时候写、什么时候忘**。

#### 一句话比喻

把它想成给 opencode 请了一个**云端私人秘书**：
- 秘书的"记事本"不在你电脑上，而在 **Supermemory 云端**（一个会做语义检索的记忆服务）；
- 你每说一句话，秘书**先翻记事本**看有没有相关的旧记忆，悄悄塞给模型看；
- 每聊完几轮，秘书**自动把对话记下来**；
- 你说"记住……"，秘书就**专门记一条**；
- 只有你（模型）明确说"删掉那条"，秘书才会**撕掉**——它自己从不主动扔东西。

#### ① 存在哪：Supermemory 云端，不是本地文件

| 问题 | 答案 |
|------|------|
| 存储介质 | **Supermemory 云服务**（默认 `api.supermemory.ai`，可自托管），不是本地 md/SQLite |
| 检索方式 | **hybrid 语义检索**（向量+关键词），相似度阈值默认 0.55，一次最多取 5 条 |
| 怎么分区 | 两级：**container（仓库级命名空间）** + **scope（personal / project）** |
| container 怎么算 | `repo_{仓库名}__{git origin 地址的哈希}` —— 所以**同一个仓库，Claude Code / Codex / Cursor / opencode 共用同一份记忆** |
| 存进去长啥样 | 每条带 metadata：来源=opencode、类型（偏好/架构/踩坑/对话…）、scope、捕获方式（自动/工具） |
| 谁决定"记什么" | **服务端的 LLM**：插件把对话整段投喂过去，Supermemory 用 LLM 过滤/蒸馏出值得留的信息 |

> 关键点：opencode 本体和这个插件都**不在本地存记忆**，真正的"记忆大脑"是云端服务。插件只是"采集 + 投喂 + 取回"的管道。

#### ② 什么时候读：你每说一句话都先翻一遍

读取由 opencode 的 `chat.message` 钩子触发（每条用户消息进来时），有三种模式：

```
direct（默认，自动）   →  插件自己搜，把结果悄悄塞进上下文，模型不用管
advisory（模型自主）   →  插件只递一句"要不要查记忆？"，模型自己决定调工具查
off                    →  不自动查
```

**direct 模式（默认）具体怎么读：**
1. **会话第一条消息**：注入"用户画像 + 项目知识"（相当于秘书先把你的基本盘摆出来）。
2. **之后每条实质提问**：拿这句话去语义搜索，命中就注入。
3. 三个"不打扰"设计：
   - **太短/是命令就跳过**（<12 字符，或以 `/`、`#`、`!` 开头）；
   - **3 秒超时就直接放弃**（fail-open），绝不卡住你干活；
   - **本次会话已经给过的记忆不再重复给**（按内容哈希去重）。
4. 注入的内容用 `<supermemory-context>` 包起来、每条前面加个 ★，让模型知道"这是从记忆里翻出来的"。

**advisory 模式**：插件不自己搜，而是每轮塞一段提示让模型判断"要不要查"，模型要查就调 `supermemory` 工具的 `search`——而且这个查询会被**自动放行**（不弹权限确认打断你）。

#### ③ 什么时候写：主要靠"自动捕获"，三条补充路

**主路——自动捕获（挂在 opencode 的生命周期事件上）：**

| 时机（opencode 事件） | 秘书做什么 |
|------|------|
| 一轮聊完、会话空闲（`session.idle`） | 把这轮对话读出来，**每满 N 轮**打包存一次（N 默认 3），后台异步、不阻塞 |
| 会话被删除（`session.deleted`） | 把还没存的零头补存 |
| opencode 关闭（`server.instance.disposed`） | 把所有活跃会话都 flush 一遍 |

几个讲究：
- **只存"完整轮次"**：一轮 = 一条用户消息 + 后续助手回复，直到助手真正答完（不是中途调工具那种）才算数。
- **幂等去重**：每个批次算一个稳定 ID（`会话ID+首末轮ID` 的哈希），同一个批次不会因为事件反复触发而被存多次。
- **隐私脱敏**：`<private>...</private>` 里的内容替换成 `[REDACTED]`，整条私密的直接不存；插件自己注入的合成上下文也会被排除。

**补充路 A——关键词触发**：你话里出现 `remember / save this / don't forget / 记住…` 等词（默认 16 个 + 可自定义），插件会塞一条提示**逼模型主动调工具存一条**（存什么、归到 project 还是 user，由模型提炼）。

**补充路 B——模型显式存**：模型随时可调 `supermemory` 工具的 `add` 主动记。

**补充路 C——压缩时沉淀**：上下文用到 80% 触发压缩时，顺手把摘要也存成一条记忆。

> **"更新"怎么发生的？** 插件没有独立的"改记忆"接口——写入都是"加一条"。真正的**合并/去重/覆盖发生在 Supermemory 服务端**（靠那个 LLM 过滤）。插件这边只保证"同一批次不重复投喂"。

#### ④ 什么时候忘：只有你明确叫它删，它才删

- **唯一入口**：模型显式调工具 `forget`，且必须给出 `memoryId`。
- **没有任何自动遗忘**：不过期、不按容量淘汰、不自动删旧。想让它忘，必须主动删。
- 搜索回来的每条记忆会标 `forgettable: true/false`，只是告诉模型"这条有没有 ID、能不能删"。
- 隐私靠"**事前不写**"（`<private>` 从不入库），而不是"事后删除"。
- 会话结束时清掉的只是**本地的读取去重缓存**，**不是**云端的记忆。

#### 全流程一张图

```
你发消息
   │
   ├─(读) chat.message 钩子 → direct: 自动语义搜索(3s超时/去重) → 注入 <supermemory-context>
   │                        → advisory: 递提示，模型自己决定调 search 工具(自动放行)
   │
   ├─(写-触发) 命中"记住"类关键词 → 塞 nudge → 模型调 add 工具存一条
   │
   └─ 对话继续…
         │
         ├─(写-自动) session.idle → 每满 N 轮 → ingestConversation → 云端(幂等ID/脱敏)
         ├─(写-压缩) 上下文 80% → 摘要也存一条
         └─(忘) 只有模型显式 forget(memoryId) 才删；无自动淘汰

存储后端：Supermemory 云端 hybrid 语义库
         container = repo_{名}__{git哈希}（跨工具共享） + scope(personal/project)
```

#### 小结：这套设计的取舍

- **优点**：跨会话、跨项目、甚至跨工具（Claude Code/Codex/Cursor）共享；语义检索比关键词聪明；自动捕获无需手动记；隐私有 `<private>` 兜底；读取全程 fail-open 不拖慢。
- **代价**：记忆**存在第三方云端**（隐私/合规需评估，虽可自托管）；要 API key、要联网；"记什么/怎么合并"黑盒在服务端 LLM；**不会自动遗忘**，长期需人工或让模型清理。

---

## 三、两者对比小结（源码级）

| 维度 | Claude Code | opencode（源码实证） |
|------|-------------|----------------------|
| 术语 | Memory（含 Auto Memory） | Rules（文档标题就是 Rules，不叫 memory） |
| 自动积累记忆 | 有：模型自动写分类记忆文件 + MEMORY.md 索引 | **无**：全库无 MEMORY.md，不自动写任何记忆 |
| 记忆分类 | user / feedback / project / reference（frontmatter type） | 无分类，只有指令文件 |
| 指令文件层级 | CLAUDE.md 四层（企业/用户/项目/子目录），全量加载 | 全局层**只取一个**文件（AGENTS.md > ~/.claude/CLAUDE.md）；项目层 **AGENTS.md > CLAUDE.md > CONTEXT.md(废弃)** 单一类型命中即停，但该类型**所有祖先层级叠加** |
| 默认加载 | 只加载 MEMORY.md 索引（≤200 行/25KB），正文按相关性召回 | `system()` 在**每个 LLM step** 重新读盘，把全局+项目+instructions 命中的文件**全量**拼进 System Prompt |
| 子目录规则 | 处理对应目录文件时加载 | `resolve()`：read 某文件时向上就近找子目录 AGENTS.md，以 `<system-reminder>` 拼进**该 read 工具输出**（非 System Prompt） |
| 去重 | —— | 三重：systemPaths 已含 / 会话内 read 已附加(extract metadata.loaded) / 同一消息内 claims |
| 扩展方式 | 分类文件 + `[[link]]` 关联 | `opencode.json` 的 `instructions`（本地路径 / GLOB / `~/` / URL，URL 5s 超时）+ AGENTS.md 内写 `@file` 引导模型自己 read |
| 兼容性 | —— | 内置 Claude Code 兼容（CLAUDE.md 作 fallback），三个 `OPENCODE_DISABLE_CLAUDE_CODE*` 开关 |

**核心差异**：
- Claude Code = **"索引常驻 + 正文按相关性召回"的双层自动记忆**，模型自己写、自己维护索引。
- opencode = **"静态指令文件每步全量注入 System Prompt"的 Rules 机制**，本体完全不自动积累；唯一的"动态"是 read 文件时就近把子目录 AGENTS.md 以 `<system-reminder>` 补进工具输出，并靠三重去重避免重复。想要真正的长期记忆得外挂 MCP。
