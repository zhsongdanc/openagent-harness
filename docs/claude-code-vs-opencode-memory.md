# Claude Code、opencode 与 Codex 的记忆系统对比

> 本文只做知识梳理，不涉及 openagent-harness 自身实现。
> 聚焦三点：① Claude Code 的 Auto Memory（user/feedback/project/reference 四类）**存哪、怎么读、怎么写、怎么更新、怎么召回**（基于官方文档 code.claude.com/docs/en/memory 与官方提示词）；② opencode 的记忆机制（**基于实际拉取的 sst/opencode 源码逐文件分析**）；③ Codex（OpenAI Codex CLI / 桌面端）的记忆机制（**基于 2026 年官方文档 learn.chatgpt.com 与 config.toml 参考核对**）。
>
> opencode 源码已 clone 到 `/tmp/opencode-src`（分支 `dev`）以便核对。第二部分（opencode）按「**原理 → 关键源码 → 解释**」三段式逐方法讲解：2.1 先给一张数据流图建立全局印象，2.2 把 `instruction.ts` 的 5 个方法一个个拆开，每个方法都先讲它干什么、再贴带行号的关键源码、最后逐行解释，一段一个方法互不交叉。

---

## 一、Claude Code 的记忆系统

Claude Code 有**两套并行**的记忆机制，加载方式完全不同：

| 机制 | 谁来写 | 内容 | 默认加载方式 |
|------|--------|------|--------------|
| **CLAUDE.md** | 开发者手写 | 指令、规范、约束 | 每次会话**全量注入**（子目录的按访问目录时加载） |
| **Auto Memory** | Claude 自动积累 | 学到的经验、模式、用户纠正 | 每次会话只加载**索引 MEMORY.md**，正文按需召回 |

用户问的 `user / feedback / project / reference` 属于 **Auto Memory** 这一套。下面 1.1～1.5 全部围绕 Auto Memory 展开（存储 → 读取 → 写入 → 更新 → 开关），1.6 补充手写的 CLAUDE.md 层级，1.7 讲记忆之外的底层机制——JSONL 会话 transcript。

### 1.1 Auto Memory 的四种 type

每条记忆是记忆目录下的**一个独立文件**（一条记忆 = 一个文件 = 一个事实），头部带 YAML frontmatter：

```markdown
---
name: <short-kebab-case-slug>
description: <one-line summary — 召回时用于判断相关性>
metadata:
  type: user | feedback | project | reference
modified: <ISO 8601 时间戳，由 Claude Code 自动写入>
---

<正文；feedback / project 类还要跟 **Why:** 和 **How to apply:** 两行>
<用 [[other-memory-name]] 关联其它记忆>
```

四类语义：

- **user** —— 用户是谁（角色、专长、偏好）
- **feedback** —— 用户对"你该怎么工作"的指导，含纠正和已确认的做法，要写清原因（why）
- **project** —— 进行中的工作 / 目标 / 约束（代码和 git 历史里推不出来的），相对日期要转成绝对日期
- **reference** —— 外部资源指针（URL、看板、工单链接）

> `type` 只是组织 / 召回用的元数据，**不决定"是否默认加载"**——四种类型待遇完全一样（见 1.3）。

### 1.2 存在哪：存储位置与文件布局

**目录**：每个项目一个独立记忆目录 `~/.claude/projects/<project>/memory/`。

- `<project>` 由 **git 仓库**推导——所以同一仓库的**所有 worktree、所有子目录共享同一份 auto memory**；不在 git 仓库里时用项目根目录名。
- 可用 `autoMemoryDirectory`（settings.json，绝对路径或 `~/` 开头）改到别处；也可用 `CLAUDE_CODE_PROJECT_DIR_NAME` + `CLAUDE_CONFIG_DIR` 让多个仓库共享一个目录。
- **机器本地**：不跨机器、不跨云环境同步。
- **不受会话清理影响**：Claude Code 按 `cleanupPeriodDays` 删旧 transcript，但**记忆目录里的文件被排除在清理之外**——`MEMORY.md` 和 topic 文件一直留着，直到你或 Claude 主动改/删。

**布局**：一个索引 + N 个 topic 文件。

```text
~/.claude/projects/<project>/memory/
├── MEMORY.md           # 索引，一条记忆一行，每次会话都加载
├── user_role.md        # 一条记忆
├── feedback_testing.md # 一条记忆
└── ...                 # Claude 创建的其它 topic 文件
```

`MEMORY.md` 是整个目录的**索引**：Claude 全程读写这个目录，靠 `MEMORY.md` 记录"什么存在哪个文件里"。

### 1.3 怎么读：默认加载 + 按需召回（核心）

**关键结论：默认进上下文的只有索引，正文靠相关性懒加载。**

1. **每次会话启动 → 只加载索引文件 `MEMORY.md`**
   - 只加载 `MEMORY.md` 的**前 200 行 / 前 25KB**（谁先到算谁），超出部分**不进上下文**。
   - `MEMORY.md` 里每条记忆一行指针：`- [Title](file.md) — hook`，**不含 frontmatter、不含正文**。
   - 无论 user / feedback / project / reference，默认进上下文的都只是各自那一行摘要。

2. **topic 文件正文按需召回（lazy recall），不是默认加载**
   - `user_role.md`、`feedback_testing.md` 这些 topic 文件**启动时不加载**。
   - 当模型判断当前任务与某条记忆的 `description` 相关、需要那份信息时，才用**标准文件工具（Read）**打开对应 `.md` 拿全文。
   - 召回的记忆被塞进 `<system-reminder>` 块，作为**背景上下文**（不是用户指令），且只代表"写入时"的状态——引用其中提到的文件/函数/参数前要重新核实是否还存在。
   - 界面出现 **"Recalled N memories"** 就是模型正在读记忆目录。

3. **子 agent 的隔离**
   - 主对话的 auto memory **不会**加载进子 agent；例外是 **fork**（继承父对话与 system prompt）。
   - 子 agent 可用自己的 `memory` 字段开启**独立的一份记忆目录**。

### 1.4 怎么写、怎么更新（用户最关心、原文档缺失的部分）

Auto Memory 的写入与更新**全由 Claude 自主完成**（用普通文件工具读写记忆目录），但遵循一整套明确约定：

**① 何时写——模型自主判断，不是每会话都写**

- Claude **不会每次会话都存东西**，而是判断"这条信息在未来的对话里是否有用"来决定值不值得记。
- 触发信号：你纠正了它、你确认了某种做法、你表达了偏好、出现了代码/git 推不出的项目上下文。
- 你也可以**显式要求**：说 "always use pnpm, not npm"、"记住 API 测试需要本地 Redis" → Claude 存进 auto memory。（想写进 CLAUDE.md 而非记忆，要说 "add this to CLAUDE.md"。）
- 界面出现 **"Saved N memories"** 就是模型正在写记忆目录。

**② 写什么 / 不写什么——两条硬性排除**

- **跳过可从代码库推导的东西**：架构、文件路径、调试修复过程等——这些看代码就有，不必记。
- **跳过 CLAUDE.md 已经写过的内容**：指令记忆里有的不重复记。
- 也跳过只对本次对话有意义的临时内容。

**③ 怎么写——一条记忆一个文件 + 维护索引**

- 新建一个 topic 文件，带 frontmatter（`name`/`description`/`metadata.type`）；feedback/project 类正文要写 **Why:** 和 **How to apply:**。
- 用 `[[other-memory-name]]` 关联其它记忆。
- **写完文件必须在 `MEMORY.md` 补一行索引**（否则这条记忆下次会话不会被"看到"，因为默认只加载索引）。

**④ 怎么更新——查重优先，改而非增**

- **先查重**：已有同类 topic 文件就**更新它**，不新建重复文件。
- **错的记忆直接删**（删文件 + 删 `MEMORY.md` 里对应索引行）。
- 每条带 frontmatter 的文件被写入时，Claude Code 自动在 frontmatter 记一个 **`modified` 字段（ISO 8601 时间戳）**——既给你看、也给模型回读时判断"这条事实有多新"。任何有 frontmatter 的文件（含旧版本创建的）下次被写时都会补上该字段；**没有 frontmatter 的文件不会被硬加**。（`modified` 需 Claude Code v2.1.214+。）

**⑤ 索引体量约束——写后自动测量并回压**

`MEMORY.md` 每次被写入后，Claude Code 会拿它对照 **200 行 / 25KB** 两个读取上限：

- **接近上限** → 提醒 Claude 精简：一条一行、把细节挪进 topic 文件、合并或丢弃过时条目。
- **超过上限** → 写入仍成功，但返回一个**错误要求 Claude 重写索引**——因为超限的部分下次加载会被直接丢弃，不重写就等于丢记忆。
- 该 200 行/25KB 限制**只针对 `MEMORY.md`**；CLAUDE.md 是另一套（最大 4 MiB 全量加载，超了跳过）。

**⑥ 怎么审计/手工编辑**

- 记忆文件都是**纯 markdown**，随时可读、可改、可删。
- `/memory` 命令浏览并打开记忆文件、切换 auto memory 开关、打开记忆目录。

> 一句话概括写入-更新闭环：**模型自主判断值得记 → 查重（有则改、错则删）→ 一条记忆写一个带 frontmatter 的 topic 文件（自动打 `modified` 时间戳）→ 同步在 `MEMORY.md` 补/改一行索引 → 索引超限则被回压要求精简。**

### 1.5 开关与自定义

- **默认开启**。`/memory` 里切换，落 `~/.claude/settings.json` 的 `autoMemoryEnabled`；单项目关闭就在项目 settings 里设 `autoMemoryEnabled: false`。
- 环境变量 `CLAUDE_CODE_DISABLE_AUTO_MEMORY=1` 全局关闭。
- `autoMemoryDirectory` 自定义存储目录（受工作区信任规则约束：开启 `permissions.blockReadsOutsideWorkingDirectories` 时，仓库自带 settings 指定的外部目录不读不写）。

### 1.6 CLAUDE.md 的层级（补充：手写指令记忆）

Auto Memory 之外，手写的 CLAUDE.md 是**全量加载**的。按加载顺序（从宽到窄，越窄越后读、越具体越优先）：

1. **企业级 managed policy**（最高，只读，不可被个人设置排除）：macOS `/Library/Application Support/ClaudeCode/CLAUDE.md`、Linux/WSL `/etc/claude-code/CLAUDE.md`、Windows `C:\Program Files\ClaudeCode\CLAUDE.md`。
2. **用户级** `~/.claude/CLAUDE.md`（对所有项目生效）。
3. **项目级** `./CLAUDE.md` 或 `./.claude/CLAUDE.md`（随 Git 提交、团队共享）。
4. **本地级** `./CLAUDE.local.md`（个人、建议 gitignore）。

补充要点：

- 从当前工作目录**逐级向上**到文件系统根，沿途的 `CLAUDE.md`/`CLAUDE.local.md` 全部**拼接**进上下文（不是覆盖）；同一目录内 `CLAUDE.local.md` 排在 `CLAUDE.md` 之后。**子目录**的 CLAUDE.md 不在启动时加载，而是 Claude 读该目录文件时**按需加载**。
- 支持 `@path/to/import` **导入**其它文件（最多递归 4 层；代码块/反引号内的 `@` 不解析）。项目级文件导入工作目录外的文件属"external import"，首次需批准。
- 可用 `.claude/rules/*.md` 拆分规则；带 `paths:` frontmatter 的规则**只在读到匹配文件时才加载**（path-scoped）。
- 仓库若用 `AGENTS.md`：默认**仅当没有 CLAUDE.md/CLAUDE.local.md 时**才读 AGENTS.md（可用 `/config` 的 Project instructions 改成两者都读等）。
- CLAUDE.md 内容是作为 **system prompt 之后的一条 user message** 投递的（不是 system prompt 本身），是"上下文"而非"强制配置"——要硬性拦截得用 PreToolUse hook。

### 1.7 会话流水账：JSONL transcript（记忆之外的底层机制）

Auto Memory / CLAUDE.md 都是"跨会话记忆"，而 Claude Code 还有一层**当前会话怎么记住自己说过什么**的机制——每段会话持续写入一份 **JSONL transcript**（会话转录），对应四层记忆模型的 L2 会话记忆。它不是记忆系统的一部分，但 `--continue`/`--resume`/`/rewind` 全靠它。

**① 存哪、长什么样**

- 路径：`~/.claude/projects/<project>/<session-id>.jsonl`。`<project>` 由工作目录路径把非字母数字字符替换成连字符得到（如在 `/home/deploy/apps/api` 启动 → 目录名 `-home-deploy-apps-api`；超 200 字符则截断并追加路径哈希）。与 1.2 的 memory 目录同在 `projects/<project>/` 下，但**一个是流水账、一个是提炼后的记忆文件**。
- JSONL = JSON Lines，**每行一条独立 JSON 记录**，会话运行中不断向文件末尾追加，像一本"按行写的流水账"：发生一笔记一笔，从不回头涂改。
- 另有配套的 `summary`（LLM 生成的会话摘要，供启动时列会话当"标题"）、`~/.claude/history.jsonl`（全局会话汇总，新版本）。

**② 为什么选 JSONL（原理）**

| 特性 | 原理 |
|------|------|
| **追加写** | 新事件只需 `append` 一行，不用重写整个文件——O(1) 写入，长会话也不卡 |
| **流式读** | 恢复时逐行读、逐行解析、逐行回放，不必把整个文件载入内存 |
| **崩溃安全** | 进程崩溃/断电时已写入的行完好，最多丢最后一行半截记录 |
| **可观测** | 纯文本，`cat session.jsonl \| jq` 直接看历史，无需专用工具 |

这套性质正是**事件溯源（event sourcing）**的落盘形态：日志是唯一真相源，状态靠回放重建。

**③ 每行记什么：type 字段决定记录身份**

- `user` / `assistant`：对话消息，内部还有 `message.role` 与 `message.content`。注意**顶层 `type: "user"` 不一定是人打的字**——content 里是 `text` 才是用户消息，是 `tool_result` 就是工具返回给模型的结果。
- `system`：运行时控制记录，按 `subtype` 恢复对应会话控制状态。
- 元数据类：`summary`（列表摘要）、`custom-title`、`tag`、`agent-name`/`agent-setting`（agent 配置）、`mode`（运行模式）、`worktree-state`（worktree 路径/分支）、`file-history-snapshot`（文件备份索引，供 `/rewind` 恢复文件内容）、`pr-link` 等——它们不参与对话回放，用于恢复"项目现场"。

**④ 消息链靠 uuid/parentUuid，不靠行序**

- 每条记录带 `uuid`（自己）与 `parentUuid`（逻辑上一条），恢复时沿链重建 `messageList`。`parentUuid` 指向的**不一定是文件里紧邻的上一行**——用户 `/rewind` 回到早先消息再重新输入时，就从同一父节点分出第二条路线（fork）。
- 一次工具调用通常形成**四条记录**：用户提问 → 模型发起 `tool_use`（assistant）→ 工具以 `tool_result` 返回（顶层 type 是 **user**）→ 模型继续作答。界面上看着是模型"连续"完成调用和结论，JSONL 里中间隔着一条保存工具结果的 user 记录。

**⑤ 恢复是两步，不是"重读一遍日志"**

`claude --continue`（当前项目最近会话）/ `--resume <id>`（指定会话，不带参数弹选择器）/ `/resume`（进程内切换）入口不同，后续逻辑一致：

1. 读 JSONL，沿 `parentUuid` 链把可恢复的对话记录整理进内存 `messageList`，并按各元数据 type 恢复 agent 配置、worktree 等工作状态——**重建一个可以继续操作的会话**；
2. 等你输入新消息后，才**整理下一次发给模型的上下文**。

**⑥ 注意边界**

- **隐私**：transcript 明文记录一切——读过的文件内容、命令输出都在里面（读过 `.env`，其内容就躺在你家目录的 JSONL 里）。分享/上传前须留意。
- **清理**：受 `settings.json` 的 `cleanupPeriodDays` 控制（默认 30 天）——与 1.2 对照：**记忆目录被排除在清理之外，transcript 不是**。
- **内部格式**：条目格式是 Claude Code 的内部细节、随版本变化，官方不建议第三方脚本直接解析（导出用 `/export`）。

### 1.8 一句话总结

> Auto Memory = **"索引常驻 + 正文按相关性召回"的双层自动记忆**。默认进上下文的只有 `MEMORY.md`（≤200 行/25KB）里每条记忆的一行索引；完整正文靠 `description` 相关性触发**按需 Read**，以 `<system-reminder>` 回灌。写入与更新全由模型自主完成——**判断值得记 → 查重改而非增 → 一条记忆一个带 frontmatter 的文件（自动打 `modified`）→ 同步维护 MEMORY.md 索引 → 超限回压精简**；可从代码/CLAUDE.md 推导的一律不记。会话层则由 **JSONL transcript**（`~/.claude/projects/<project>/<session-id>.jsonl`）承载：每行一条事件、追加写、uuid/parentUuid 连链，`--continue`/`--resume`/`/rewind` 全靠回放它重建现场——日志是真相源，记忆是提炼物。

---

## 二、opencode 的记忆系统（基于源码分析）

> 本节基于实际拉取的 sst/opencode 源码：`packages/opencode/src/session/instruction.ts`、`packages/core/src/fs-util.ts`、`packages/opencode/src/tool/read.ts`、`packages/opencode/src/session/prompt.ts`、`packages/core/src/config.ts`、`packages/core/src/global.ts`、`packages/web/src/content/docs/rules.mdx`。
>
> **阅读指引**：opencode 的"记忆"逻辑全集中在 `instruction.ts` 一个文件，对外只有 5 个方法。下面 2.1 先给一张整体数据流图建立全局印象，2.2 再**逐个方法**拆解——每个方法都按「**原理**（大白话讲清它干吗）→ **关键源码** → **解释**」三段式来讲。**正文尽量不嵌函数名/行号**，方便阅读；所有源码位置（文件名、行号、辅助函数）统一收在每段代码块下方的「**涉及源码**」备注里，需要核对时再看。

### 2.0 最重要的结论：opencode 没有"自动记忆"

全代码库 grep `MEMORY.md` / memory 写入逻辑 = **零命中**（`memory` 关键字只出现在 xai 插件、`cli/heap.ts`（指 RAM）、`beast.txt` 提示词里，与记忆无关）。opencode 官方文档里这套东西叫 **Rules**，不叫 memory。

它的"记忆"本质是：**一组静态指令文件（AGENTS.md 等），在每次 LLM 请求时被读出来拼进 System Prompt**。没有 Claude Code 那种"模型自动写记忆文件 + 索引 + 按需召回"的机制。跨会话积累的动态记忆，opencode 本体不做，要靠外部 MCP / 插件。

所以理解 opencode 的"记忆"，本质就是理解**「哪些文件会被读进来、什么时候读、拼到哪去」**这一件事——全部由 `instruction.ts` 的 5 个方法完成：

| 方法 | 一句话职责 |
|------|-----------|
| `systemPaths()` | 算出"该常驻 System Prompt 的文件"路径集合 |
| `system()` | 把上面的路径逐个读盘，拼成 System Prompt 字符串数组 |
| `find(dir)` | 找某个目录下第一个存在的指令文件（`resolve` 的辅助） |
| `resolve()` | read 某文件时，就近把子目录指令附加进那次工具输出 |
| `clear(msgID)` | 清掉某条消息的"就近附加"去重记录 |

### 2.1 一图看懂整体数据流

opencode 的指令文件有**两条进入上下文的路**，触发时机和落点都不同：

```
【常驻路】每次 LLM step 都走 —— 进 System Prompt
  systemPaths()  ──算出文件集──▶  system()  ──逐个读盘──▶  ["Instructions from: <路径>\n<内容>", ...]
      │                                                              │
      ├─ 全局层：~/.config/opencode/AGENTS.md、~/.claude/CLAUDE.md（只取第一个存在的）
      ├─ 项目层：从 cwd 向上到 worktree 根，AGENTS.md > CLAUDE.md > CONTEXT.md（第一种命中即停，但该类型每层都叠加）
      └─ config.instructions：GLOB / 绝对路径 / ~/ / URL
                                                                     ▼
                                       prompt.ts: system = [...env, ...instructions, ...mcp, ...skills]

【就近路】只有 read 工具读文件时才走 —— 进那次 read 的输出，不进 System Prompt
  read 一个子目录文件 ──▶ resolve() ──从该文件目录向上找到项目根──▶ 就近的子目录 AGENTS.md
                                                                     │
                                          用 <system-reminder> 包裹，拼进 read 工具输出（三重去重）
```

下面 2.2 按这条数据流的顺序，逐个方法拆开讲。

### 2.2 五个方法逐个拆解

#### 2.2.1 两组候选文件名：写死在源码里的"找什么"

**原理**：opencode 不认识"任意记忆文件"，它只在两个写死的候选清单里找：一个**全局层清单**、一个**项目层清单**。CLAUDE.md 是否进清单，由 Claude Code 兼容开关决定；CONTEXT.md 是历史遗留的废弃兜底。

**关键源码（`instruction.ts:60-68`）**：

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

**解释**：全局层目录就是 `~/.config/opencode`（可用环境变量 `OPENCODE_CONFIG_DIR` 改）。两个清单的元素**顺序即优先级**——后面选文件时正是按清单顺序逐个试、命中即停，所以 AGENTS.md 永远排在 CLAUDE.md 前面。

#### 2.2.2 systemPaths()：算出"该常驻 System Prompt 的文件集"

**原理**：这是常驻路的"选文件"环节，产出一个去重后的路径集合。三条规则按顺序叠加：① **全局层只取第一个存在的文件**（两个全局候选只有一个生效，不叠加）；② **项目层第一种命中的文件名类型即停，但该类型的所有祖先层级全叠加**（只要有 AGENTS.md 就完全不看 CLAUDE.md，但从当前目录到仓库根每一层的 AGENTS.md 都收）；③ **配置里额外声明的补充文件**（支持通配符 / 绝对路径 / `~/` / URL，其中 URL 先跳过、留给读盘环节去抓取）。

**关键源码（`instruction.ts:110-153`）**：

```ts
const systemPaths = Effect.fn("Instruction.systemPaths")(function* () {
  const config = yield* cfg.get()
  const ctx = yield* InstanceState.context
  const paths = new Set<string>()

  for (const file of globalFiles) {                 // ① 全局层
    if (yield* fs.existsSafe(file)) {
      paths.add(path.resolve(file))
      break                                          //    命中第一个就停，不叠加
    }
  }

  // The first project-level match wins so we don't stack AGENTS.md/CLAUDE.md from every ancestor.
  if (!Flag.OPENCODE_DISABLE_PROJECT_CONFIG) {
    for (const file of instructionFiles) {           // ② 项目层：AGENTS.md → CLAUDE.md → CONTEXT.md
      const matches = yield* fs
        .findUp(file, ctx.directory, ctx.worktree)   //    从 cwd 向上收集到 worktree 根的全部同名文件
        .pipe(Effect.catch(() => Effect.succeed([])))
      if (matches.length > 0) {
        matches.forEach((item) => paths.add(path.resolve(item)))  // 该类型每层都加
        break                                                     // 但不再看下一种文件名
      }
    }
  }

  if (config.instructions) {                         // ③ 补充文件
    for (const raw of config.instructions) {
      if (raw.startsWith("https://") || raw.startsWith("http://")) continue  // URL 交给 system()
      const instruction = raw.startsWith("~/") ? path.join(global.home, raw.slice(2)) : raw
      const matches = yield* (
        path.isAbsolute(instruction)
          ? fs.glob(path.basename(instruction), { cwd: path.dirname(instruction), absolute: true, include: "file" })
          : relative(instruction)                    // 相对路径 → globUp（每层祖先都 glob）
      ).pipe(Effect.catch(() => Effect.succeed([] as string[])))
      matches.forEach((item) => paths.add(path.resolve(item)))
    }
  }

  return paths
})
```

**解释**：
- 规则 ② 是理解重点，源码注释一句话说清了：*"第一个项目层命中即胜出，这样就不会把每个祖先目录的 AGENTS.md/CLAUDE.md 全叠进来。"* 精确含义是——**在"文件名类型"这一层命中即停**（有 AGENTS.md 就不看 CLAUDE.md），**但同一类型内部、从当前目录到仓库根每一层的同名文件都会收**，这就是 monorepo 里子包和根的 AGENTS.md 能同时生效的原因。
- 规则 ② 的"逐层向上收集"和规则 ③ 的"逐层通配"，底层各由一个上溯工具完成（都从当前目录逐级走到仓库根）：前者按固定文件名找、返回全部命中；后者按通配模式找、每层匹配一遍再累加，所以像 `packages/*/AGENTS.md`、`.cursor/rules/*.md` 这种模式会在上溯路径上逐层展开。
- 规则 ③ 的补充文件来自用户配置（一个字符串数组，多份配置合并时取并集去重）。
- 另有一个总开关：置位后，项目层和相对通配都改为只在配置目录内找，不再上溯项目目录。

> **涉及源码**：选文件主逻辑 `instruction.ts:110-153`（规则②注释在 122 行）；两个上溯工具在 `fs-util.ts`——按文件名找 `findUp`(154-166)、按通配找 `globUp`(184-198)；补充文件配置项 `core/src/config.ts:96`（合并去重 48-49）；总开关环境变量 `OPENCODE_DISABLE_PROJECT_CONFIG`。

#### 2.2.3 system()：每一步 LLM 调用都重新读盘注入

**原理**：上一步选出的路径集，会在 agent loop 的**每一步**被重新读一遍盘，把每份文件内容拼成带来源前缀的字符串，最终并进 System Prompt。这意味着**改 AGENTS.md 无需重启，下一步立即生效**。

**关键源码（`instruction.ts:155-169`）**：

```ts
const system = Effect.fn("Instruction.system")(function* () {
  const config = yield* cfg.get()
  const paths = yield* systemPaths()
  const urls = (config.instructions ?? []).filter(
    (item) => item.startsWith("https://") || item.startsWith("http://"),
  )

  const files = yield* Effect.forEach(Array.from(paths), read, { concurrency: 8 })
  const remote = yield* Effect.forEach(urls, fetch, { concurrency: 4 })

  return [
    ...Array.from(paths).flatMap((item, i) => (files[i] ? [`Instructions from: ${item}\n${files[i]}`] : [])),
    ...urls.flatMap((item, i) => (remote[i] ? [`Instructions from: ${item}\n${remote[i]}`] : [])),
  ]
})
```

**解释**：本地文件并发读（并发度 8）、URL 并发抓（并发度 4），两者拼成一个字符串数组返回，每项都带来源前缀标明出处。这个数组随后和环境信息、MCP 指令、技能等一起，被拼成最终发给模型的 system（拼接点见下方代码）：

```ts
const [skills, env, instructions, mcpInstructions, modelMsgs] = yield* Effect.all([
  sys.skills(agent),
  sys.environment(model),
  instruction.system().pipe(Effect.orDie),   // ← 每个 step 都调一次
  sys.mcp(agent, session.permission),
  MessageV2.toModelMessagesEffect(msgs, model),
])
const system = [
  ...env,
  ...instructions,                            // ← system() 的返回值落在这里
  ...(mcpInstructions ? [mcpInstructions] : []),
  ...(skills ? [skills] : []),
]
```

其中 URL 抓取设了 **5 秒超时**、失败静默返回空串，不会因一个坏 URL 拖垮整步。

> **涉及源码**：本函数 `instruction.ts:155-169`；URL 抓取 `instruction.ts:95-103`；拼进最终 system `prompt.ts:1257-1269`。

#### 2.2.4 find(dir)：找某目录下第一个存在的指令文件

**原理**：给定一个目录，按项目层清单的顺序（AGENTS.md → CLAUDE.md → CONTEXT.md）返回**第一个存在**的指令文件路径，找不到就返回空。它是"就近附加"时的查找基础。

**关键源码（`instruction.ts:171-177`）**：

```ts
const find = Effect.fn("Instruction.find")(function* (dir: string) {
  for (const file of instructionFiles) {
    const filepath = path.resolve(path.join(dir, file))
    if (yield* fs.existsSafe(filepath)) return filepath
  }
  return undefined
})
```

**解释**：注意这里"命中即返回"——**一个目录只认一个指令文件**（有 AGENTS.md 就不看同目录的 CLAUDE.md）。这和项目层"每层叠加"不冲突：那是跨目录收集，这是单目录取一。

#### 2.2.5 resolve()：read 文件时"就近"附加子目录指令

**原理**：这是 opencode 里唯一带"按需/动态"味道的机制，由 **read 工具**触发（不是启动时）。当你读某个子目录下的文件时，它会从被读文件的目录**一路向上走到项目根**，把沿途"就近"的子目录指令文件找出来附加进上下文。附加方式很关键——**不塞进 System Prompt，而是拼进那次 read 工具的输出里，用 `<system-reminder>` 包裹**（与 Claude Code 召回记忆的手法一致，但触发条件是"读了该目录的文件"而非"语义相关性"）。

**关键源码（`instruction.ts:179-221`）**：

```ts
const resolve = Effect.fn("Instruction.resolve")(function* (messages, filepath, messageID) {
  const sys = yield* systemPaths()          // 去重①：已在 System Prompt 里的
  const already = extract(messages)         // 去重②：本会话此前 read 已附加过的
  const results = []
  const s = yield* InstanceState.get(state) // s.claims：去重③的载体
  const root = path.resolve(yield* InstanceState.directory)

  const target = path.resolve(filepath)
  let current = path.dirname(target)

  // Walk upward from the file being read and attach nearby instruction files once per message.
  while (current.startsWith(root) && current !== root) {   // 从被读文件目录向上走到项目根
    const found = yield* find(current)                     // 该目录第一个存在的指令文件
    if (!found || found === target || sys.has(found) || already.has(found)) {
      current = path.dirname(current); continue            // 命中任一去重条件 → 跳过、上移
    }
    let set = s.claims.get(messageID)
    if (!set) { set = new Set(); s.claims.set(messageID, set) }
    if (set.has(found)) { current = path.dirname(current); continue }  // 去重③：本消息内已附加
    set.add(found)
    const content = yield* read(found)
    if (content) results.push({ filepath: found, content: `Instructions from: ${found}\n${content}` })
    current = path.dirname(current)
  }
  return results
})
```

read 工具拿到就近查找的结果后，用 `<system-reminder>` 拼进输出：

```ts
const loaded = yield* instruction.resolve(ctx.messages, filepath, ctx.messageID)
// ... 组装 output（文件内容）...
if (loaded.length > 0) {
  output += `\n\n<system-reminder>\n${loaded.map((item) => item.content).join("\n\n")}\n</system-reminder>`
}
// 同时把路径记进 metadata.loaded，供下次 extract() 去重
return { title, output, metadata: { preview, truncated, loaded: loaded.map((item) => item.filepath), ... } }
```

**解释——三重去重**（保证同一份子目录指令不会反复灌进上下文）：
- **去重①**：已经常驻 System Prompt 的文件，不再就近附加。
- **去重②**：扫描本会话此前所有已完成的 read 记录（它们把附加过的文件路径存在各自的元数据里，被压缩掉的跳过），之前附加过的不再附加。
- **去重③**：**同一条助手消息内**每个文件只附加一次；这条消息结束时清掉记录（见 2.2.6）。

> **涉及源码**：就近查找主逻辑 `instruction.ts:179-221`；拼进工具输出 `read.ts:300/355-356`；去重②扫描历史 read 元数据 `instruction.ts:17-32`。

#### 2.2.6 clear(msgID)：清掉某条消息的就近附加去重记录

**原理**：就近附加的去重③会把"本消息已附加过哪些文件"记在一张按消息 ID 索引的表里。一条助手消息处理完，必须把它的记录清掉，否则这张表会无限增长、且下一条消息会被错误去重。

**关键源码（`instruction.ts:105-108`）**：

```ts
const clear = Effect.fn("Instruction.clear")(function* (messageID: MessageID) {
  const s = yield* InstanceState.get(state)
  s.claims.delete(messageID)
})
```

**解释**：运行时在两处保证它一定被调用——消息开始时挂一个收尾钩子、每步结束时再兜底清一次。注意这里清掉的只是**读取去重状态**，不是"遗忘记忆"——opencode 没有遗忘机制（静态文件想删得手动删）。

> **涉及源码**：本函数 `instruction.ts:105-108`；两处调用点 `prompt.ts:691`（收尾钩子）、`prompt.ts:1331`（每步兜底）。

### 2.3 Claude Code 兼容开关

**原理**：前面多处提到的"Claude Code 兼容开关"来自运行时标志，用三个环境变量控制是否兼容 Claude Code 的 `.claude` 生态。

**关键源码（`runtime-flags.ts:23-30`）**：

```ts
disableClaudeCodePrompt: Config.all({
  broad: bool("OPENCODE_DISABLE_CLAUDE_CODE"),
  direct: bool("OPENCODE_DISABLE_CLAUDE_CODE_PROMPT"),
}).pipe(Config.map((flags) => flags.broad || flags.direct)),
disableClaudeCodeSkills: Config.all({
  broad: bool("OPENCODE_DISABLE_CLAUDE_CODE"),
  direct: bool("OPENCODE_DISABLE_CLAUDE_CODE_SKILLS"),
}).pipe(Config.map((flags) => flags.broad || flags.direct)),
```

**解释**：

```bash
export OPENCODE_DISABLE_CLAUDE_CODE=1         # 关掉全部 .claude 支持（broad，同时置位上面两个）
export OPENCODE_DISABLE_CLAUDE_CODE_PROMPT=1  # 只关 ~/.claude/CLAUDE.md 与项目 CLAUDE.md
export OPENCODE_DISABLE_CLAUDE_CODE_SKILLS=1  # 只关 .claude/skills
```

这个开关一旦打开，CLAUDE.md 项在构造候选清单时就被剔除（见 2.2.1），后续选文件、就近查找自然都不会再找到它。

> **涉及源码**：标志定义 `runtime-flags.ts:23-30`。

### 2.4 `/init` 命令：唯一的官方"写入"入口

`/init`（`command/index.ts:72` "guided AGENTS.md setup"，模板 `command/template/initialize.txt`）会扫描仓库、必要时问几个问题，然后**创建或就地改进** AGENTS.md，聚焦"未来会话最可能需要的硬信息"：build/lint/test 命令、命令顺序、非显而易见的架构与目录结构、项目特有约定/坑、以及对 Cursor/Copilot 既有规则的引用。已存在则增量改进而非盲目重写。

### 2.5 动态长期记忆怎么办（本体不写，靠外挂）

opencode 本体不提供"模型自动写记忆文件"的能力（2.0 已从源码确认）。要做跨会话积累的偏好/项目事实，只能靠**外挂**，有两条载体（MCP 只是其中一种，非唯一）：

1. **MCP server** —— opencode 有一等公民的 MCP 客户端支持（`packages/core/src/config/mcp.ts`：`local`(command) / `remote`(url) 两种），在 `opencode.json` 的 `mcp` 字段配置，接一个 memory MCP 即可。
2. **生态插件** —— 官方生态文档 `packages/web/src/content/docs/ecosystem.mdx:42` 明确把跨会话记忆列为插件：`opencode-supermemory —— Persistent memory across sessions using Supermemory`。

> 另一个易误判的细节：`packages/opencode/src/session/prompt/beast.txt:113-123` 有一段 "# Memory"，告诉模型"你有一个记忆文件 `.github/instructions/memory.instruction.md`，用户让你记就更新它"。但这**只是某个可选提示词的约定**（默认提示词 `default.txt` 没有），本质是"让模型用普通 read/write 工具自己读写一个 md 文件"——没有记忆子系统、没有索引、没有自动抽取/召回，且那个文件也不会被自动加载（不属于 AGENTS.md/CLAUDE.md/CONTEXT.md，除非在 `instructions` 里注册）。够不上 Claude Code 意义上的 auto-memory。

### 2.6 插件如何管理记忆：opencode 给钩子，插件全权负责（源码级）

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

### 2.7 案例剖析：opencode-supermemory 是怎么做记忆的（通俗版）

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

## 三、Codex 的记忆系统

> 本节基于 OpenAI Codex 官方文档（2026 年已从 `developers.openai.com/codex` 迁至 `learn.chatgpt.com`）、`~/.codex/config.toml` 配置参考，以及 Codex CLI 0.147+ 的 AGENTS.md 发现规则整理。Codex 的"记忆"与 Claude Code 是**两条完全不同的哲学**：Claude Code 的 Auto Memory 默认开、按仓库分目录、鼓励你直接读改删；Codex 的 Memories 默认关、有地区红线、后台异步生成、且官方明确"这些是生成态状态文件，别手抠"。

### 3.0 最重要的结论：Codex 的"记忆"是三套，不是一套

和 Claude Code（CLAUDE.md + Auto Memory 两套）类似，Codex 也把"记东西"拆成职责不同的几套系统，且**刻意把"确定性指令"与"概率性回忆"分开**：

| 机制 | 谁来写 | 内容 | 默认 | 可靠性 |
|------|--------|------|------|--------|
| **AGENTS.md** | 开发者手写 | 必须每次生效的指令、规范、禁区 | 一直生效（放进仓库就读） | **确定性**——每轮开工必读 |
| **Memories** | Codex 自动生成 | 从过往会话学到的稳定上下文（技术栈/惯例/踩坑） | **默认关闭**，需手动开 | **概率性**——后台异步生成、不保证记到 |
| **Chronicle**（实验性） | Codex 从屏幕内容生成 | 用最近屏幕上下文补料 Memories | 需主动开启 | 仅 Pro + macOS |

官方一句话定了调，也是理解 Codex 记忆观的钥匙：

> 把必须始终生效的团队规矩，留在 **AGENTS.md** 或签入仓库的文档里。把记忆（Memories）当成一个有用的**本地回忆层**，而不是那些必须永远生效的规则的唯一来源。

翻成人话：**Memories 是锦上添花，不是兜底保险**。"依赖只用 pnpm"这类铁律必须写进 AGENTS.md（每轮必读、确定性），赌 Memories 迟早翻车——它可能还没生成、这个会话可能没注入、合并时可能被挤掉。这与 Claude Code "Auto Memory 默认开、可依赖"的定位形成鲜明对比。

> 另有一条容易被归进"记忆"的底层机制——**会话持久化（session rollout）+ compaction**，见 3.4。它对应四层记忆模型里的 L1/L2（工作记忆 + 会话记忆），与 AGENTS.md（L3 指令记忆）、Memories（L4 长期记忆）分属不同层，别混为一谈。

### 3.1 AGENTS.md：分层指令记忆（确定性、每轮必读）

AGENTS.md 是 Codex 开工前读取的项目级指令文件（普通 Markdown，无强制字段），格式已成跨工具事实标准（Codex / Cursor / Jules 等共用，由 Linux Foundation 旗下 Agentic AI Foundation 管理）。它的核心是**三层发现 + root-first 合并**：

**① 发现机制（每次运行重建，无缓存）**

1. **全局层**：在 Codex home（默认 `~/.codex`，可被 `CODEX_HOME` 改）里先找 `AGENTS.override.md`，不存在再找 `AGENTS.md`——**只取第一个非空文件**。
2. **项目层**：从项目根（通常是含 `.git` 的目录，可由 `project_doc_max_bytes` 等配置改根标记）**逐级向下**扫到当前工作目录；每一层依次检查 `AGENTS.override.md → AGENTS.md → project_doc_fallback_filenames`（自定义备用名，默认为空）——**每个目录最多纳入一份**（有 override 就不看同目录 AGENTS.md，不是相加）。
3. **合并层**：从根到当前目录 **root-first 拼接**，越靠近工作目录的文件出现越晚，因此**覆盖**前面的通用规则。

**② 两条硬边界**

- **空文件跳过**：该层不贡献约定。
- **总大小上限**：合并后达到 `project_doc_max_bytes`（**默认 32 KiB**）即停止加载，超限部分被截断——要么调高上限，要么把内容拆到嵌套子目录。

**③ 生效时机**

指令链**在每次运行开始时构建一次**（TUI 里对应一次启动会话），改完磁盘上的 AGENTS.md **不会**自动灌进已打开的窗口，需新开一次运行/会话才重新读取。这点与 opencode 的 `system()` **每个 LLM step 都重新读盘**（改完下一步立即生效）截然不同。

```text
~/.codex/AGENTS.md                        # 全局：所有项目的个人默认约定
repo/AGENTS.md                            # 仓库根：团队共识
repo/apps/web/AGENTS.md                   # 子模块规则
repo/apps/web/tests/AGENTS.override.md    # 测试目录临时覆盖（同层优先于 AGENTS.md）
```

> 与 CLAUDE.md 的关系：Codex 可把 `CLAUDE.md` 配成 `project_doc_fallback_filenames` 里的**备用名**，但"备用"含义是——只有当同目录的 `AGENTS.override.md`、`AGENTS.md` 都不存在/为空时才可能选到它，**一个目录仍只取一份**，不会把 CLAUDE.md 追加进来。

### 3.2 Memories：Codex 自己写的那本笔记（概率性、默认关）

Memories 解决"不想每开一个新会话都把技术栈/惯例从头交代一遍"的痛点——它把过往会话里**稳定、可复用**的上下文带到未来会话。这是 Codex 里唯一"模型自动积累"的记忆，但设计得比 Claude Code 克制得多。

**① 默认关闭 + 地区红线（与 Claude Code 最大差异）**

- Memories **默认关闭**，需显式开启：`~/.codex/config.toml` 的 `[features]` 段写 `memories = true`，或在 Codex App 设置里打开。
- **在欧洲经济区（EEA）、英国、瑞士上线时不可用**——这是功能层面按区域关掉的，不是网络问题。（Claude Code 的 Auto Memory 则默认开、无地区限制。）

**② 怎么写进去——关键：后台异步，不是实时**

这是 Codex 记忆最反直觉的一点，拆成四条：

1. **从"够格的过往会话"提炼**，不是每次都记——会跳过仍在进行中、太短命的会话，免得把没干完的活瞎总结。
2. **后台异步 + 等"闲够久"**：记忆不会在会话一结束就更新，Codex 会等该会话空闲足够长时间（官方默认 `min_rollout_idle_hours` 约 **6 小时**）、确认你不是还在干，才在后台总结。所以"刚说完就考它"必然落空。
3. **配额紧张时跳过**：当速率限制（rate-limit）剩余百分比低于阈值（`min_rate_limit_remaining_percent`，默认 **25**）时，这趟后台生成被跳过，不跟正常任务抢额度。
4. **密钥脱敏**：生成时对记忆字段里的 secret 做 redaction——但这是"尽量帮你擦"，**不是许可**（记忆是不加密的明文 Markdown 落盘）。

提取与合并由两个可覆盖的模型驱动：`extract_model`（逐会话记忆提取）、`consolidation_model`（全局记忆合并）。

**③ 存在哪**

```text
~/.codex/memories/
├── (摘要文件)
├── (持久条目文件)
├── (近期输入文件)
└── (过往会话佐证文件)
```

- 在 Codex home（默认 `~/.codex`，跟 `CODEX_HOME` 走）下，**机器本地、不进 git**。
- 官方明确定性为"**生成的状态**"：排障或分享 `~/.codex` 前可查看，但**别把手动改这些文件当主要控制手段**——想控制记忆行为用 `/memories` 和配置项。（这与 Claude Code 鼓励你 `/memory` 直接开文件夹读改删相反。）

**④ 怎么控制——两层**

- **会话级 `/memories`**（TUI / App 内敲）：只控当前会话——要不要用已有记忆、要不要拿这个会话生成新记忆、或干脆关掉。**不改全局设置**，下次新会话仍按全局配置。（类比录音笔上独立的"录"和"放"两个键。）
- **全局级 `config.toml` 的 `[memories]` 段**：

| 配置项 | 作用 | 默认 |
|--------|------|------|
| `memories.use_memories` | 设 false 则不把已有记忆注入未来会话 | true |
| `memories.generate_memories` | 设 false 则新会话不被拿去当生成记忆的输入 | true |
| `memories.disable_on_external_context` | 设 true 则用过 MCP / web search / 工具搜索等外部上下文的会话不参与生成（旧别名 `no_memories_if_mcp_or_web_search`） | false |
| `memories.min_rate_limit_remaining_percent` | 速率限额剩余低于此百分比就不启动生成 | 25 |
| `memories.extract_model` / `consolidation_model` | 覆盖"逐会话提取" / "全局合并"用的模型 | 默认 |

**⑤ 该记 vs 不该记（含一条红线）**

- ✅ **它的主场**：常用技术栈、反复出现的工作流、项目惯例、踩过的坑——稳定、可复用、它能自学。
- ❌ **别指望它记**："必须每次生效"的死规矩（→ 写 AGENTS.md）、一次性/马上要改的临时状态（端口号、"我现在在调登录页"）。
- 🚫 **红线**：密码 / API key / token **绝对不进任何记忆文件**——明文落盘，脱敏只是兜底不是许可；分享 `~/.codex` 或导出记忆产物前，先去 `~/.codex/memories/` 扫一眼。

### 3.3 Chronicle：用屏幕内容喂记忆（实验性、Codex 独有）

Chronicle 是 Codex 区别于 Claude Code / opencode 的特色能力——普通 Memories 从"对话"里学，Chronicle 更进一步从"屏幕上的内容"里学，用最近的屏幕上下文给 Memories 补料（认出该用哪个源：具体文件 / Slack / Google Doc / 仪表盘 / PR，再直接去用那个源）。

**硬限制（比 Memories 还硬，三道门槛缺一不可）**：

- **订阅**：仅 ChatGPT **Pro**（Plus 不行）。
- **平台**：仅 **macOS**（Windows / Linux 都没有）。
- **地区**：EU / 英国 / 瑞士不支持。
- 需两项 macOS 系统权限：**屏幕录制** + **辅助功能**。

**三条隐私警告（开之前必须看懂）**：

1. **吃配额很快**：靠后台跑沙箱代理从截屏生成记忆，消耗速率限额很猛。
2. **增加提示注入风险**：读的是你控制不了的屏幕内容——浏览藏有恶意 agent 指令的网站时，Codex 可能跟着走。
3. **记忆不加密存本地**：和其它 Codex 记忆一样是明文 Markdown。

**数据存哪 / 传不传给 OpenAI**：

- 屏幕截图**临时**存本机（运行时可能在 `$TMPDIR/chronicle/screen_recording/`），超 6 小时的运行中删除。
- 生成的记忆存 `$CODEX_HOME/memories_extensions/chronicle/`（通常 `~/.codex/memories_extensions/chronicle`），是可读可改的 Markdown——想让它忘掉某事就删/改对应文件，但**别手动往里加新信息**。
- 截图在 OpenAI 服务器处理完**即弃**（除非法律要求，不留存、不用于训练）。
- 敏感场景用菜单栏 Codex 图标 **Pause Chronicle**（恢复 Resume）；未经他人同意别拿它记录会议/沟通。

### 3.4 会话持久化 + compaction（L1/L2 工作记忆与会话记忆）

除了上面三套"跨会话记忆"，Codex 还有一层"当前会话怎么记住自己说过什么"的机制，对应四层记忆模型的 L1/L2。它和 Claude Code 的 transcript（1.7）异曲同工，**同样选择了 JSONL 作为落盘格式**：

**① Rollout 落盘：为什么必须落、为什么是 JSONL**

- **为什么必须落盘**：Codex 放弃 server-side state（为支持 ZDR 零数据保留客户），每次请求需自包含完整历史——本地 rollout 文件就是这份历史的唯一真相源。
- **存哪**：完整对话历史（含所有 tool call 与结果）追加写入 `~/.codex/sessions/YYYY/MM/DD/rollout-*.jsonl`（按日期分目录，文件名末尾 UUID 即 session id）；另有 `~/.codex/history.jsonl`（输入历史）、`session_index.jsonl` / `state_5.sqlite` 做索引，归档会话移到 `~/.codex/archived_sessions/`。
- **为什么是 JSONL**（与 Claude Code transcript 同一套原理）：每行一条独立 JSON 记录，新事件只需 **append 一行**不重写全文件；恢复时**逐行流式读**回放；进程崩溃时已写入的行完好（**崩溃安全**）；纯文本可直接 `jq` 审计。本质也是**事件溯源**：状态不落盘，靠回放日志重建。
- **与 Claude Code 的差异**：Codex 用 `session_index.jsonl` / `state_5.sqlite` 另建**索引层**加速检索（Claude Code 靠目录扫描 + summary 文件）；Codex 提供 `codex exec --ephemeral` 显式**不落盘**模式。

**② 恢复 / fork**

`codex resume --last` / `codex resume <id>` 从 rollout 逐行回放重建上下文；`codex fork --last` 派生到新线程（新开一份 rollout，不动原文件——追加写格式天然支持分叉）。

**③ Compaction（L1 压缩）**

上下文超阈值时调用一个特殊 Responses API endpoint，生成更小的代表版本替换原历史——技术上不是简单 summary，而是**加密的 latent state payload**。压缩产物同样追加进 rollout（历史不删，只追加"新的代表版本"，保持 append-only 语义）。

**④ 硬约束**

AGENTS.md 里明确要求"注入模型上下文的一切必须有界、有硬上限，单项不超过 10K tokens"，防止记忆污染。

> **对照小结（JSONL 会话日志）**：两家都选了 JSONL + append-only + 回放重建，因为 agent 会话天然是"高频追加、从不回改、崩溃不能丢"的事件流；差异在**链结构**——Claude Code 每行带 `uuid/parentUuid` 支持 rewind/fork 分叉，Codex 按日期目录组织 + 独立索引层；而 opencode 本体走的是另一套（SDK 存储 + 插件自建），不在此列。

### 3.5 一句话总结

> Codex 的记忆 = **"确定性指令（AGENTS.md）+ 概率性回忆（Memories）"双通路，外加实验性的屏幕喂记忆（Chronicle）与底层的会话 rollout/compaction**。AGENTS.md 三层发现、root-first 合并、32 KiB 上限、每次运行重建（改完需新开会话）；Memories **默认关**（EEA/UK/瑞士不可用）、**后台异步**生成（等会话闲够久 ~6h、跳过短命会话、配额低于 25% 跳过、密钥脱敏）、存 `~/.codex/memories/` 是"生成态状态别手抠"、靠 `/memories` + `[memories]` 旋钮控制。**核心哲学：必须每次生效的死规矩写 AGENTS.md，别赌 Memories 兜底。**

---

## 四、三者对比小结（源码级）

| 维度 | Claude Code | opencode（源码实证） | Codex（官方文档） |
|------|-------------|----------------------|-------------------|
| 术语 | Memory（含 Auto Memory） | Rules（文档标题就是 Rules，不叫 memory） | AGENTS.md（指令）+ Memories（自动记忆）+ Chronicle（屏幕） |
| 存储介质 | 本地 md：`~/.claude/projects/<project>/memory/`（MEMORY.md 索引 + topic 文件） | 本地静态 md 指令文件（AGENTS.md 等），**无索引、无分类、无记忆库** | AGENTS.md 在仓库里；Memories 在 `~/.codex/memories/`（生成态状态文件）；会话 rollout 在 `~/.codex/sessions/` |
| 自动积累记忆 | 有：模型自主写分类记忆文件 + 维护 MEMORY.md 索引 | **无**：全库无 MEMORY.md，不自动写任何记忆；唯一写入是 `/init` 生成 AGENTS.md | **有但默认关**：Memories 后台异步生成（非实时）；Chronicle 从屏幕生成 |
| 记忆分类 | user / feedback / project / reference（frontmatter type） | 无分类，只有指令文件 | 无显式 type；分摘要 / 持久条目 / 近期输入 / 佐证几类文件 |
| 指令文件层级 | CLAUDE.md 四层（managed/user/project/local），全量拼接、子目录按需 | 全局层**只取一个**文件（AGENTS.md > ~/.claude/CLAUDE.md）；项目层 **AGENTS.md > CLAUDE.md > CONTEXT.md(废弃)** 单一类型命中即停，但该类型**所有祖先层级叠加** | 全局层 `~/.codex/AGENTS.override.md`→`AGENTS.md`（取第一个非空）；项目层从 git 根→cwd 逐层，每目录 override→md→fallback **只取一份**，root-first 合并覆盖 |
| 默认加载（读） | 只加载 MEMORY.md 索引（≤200 行/25KB），topic 正文按相关性 Read 召回，`<system-reminder>` 回灌 | `system()` 在**每个 LLM step** 重新读盘，把全局+项目+instructions 命中的文件**全量**拼进 System Prompt | AGENTS.md **每次运行构建一次**（改完需新开会话），合并上限 32 KiB；Memories 由 `use_memories` 注入新会话 |
| 子目录规则 | 处理对应目录文件时按需加载 | `resolve()`：read 某文件时向上就近找子目录 AGENTS.md，以 `<system-reminder>` 拼进**该 read 工具输出**（非 System Prompt） | 子目录 AGENTS.md 启动时即按 root-first 合并进指令链（非按需），越近越优先 |
| 去重 | 写入侧先查重（有则改、错则删） | 读取侧三重：systemPaths 已含 / 会话内 read 已附加(extract metadata.loaded) / 同一消息内 claims | 同目录只取一份指令文件；Memories 合并由 `consolidation_model` 服务端整合 |
| 写入触发 | 模型自主判断"未来是否有用" + 用户显式"记住X"（Saved N memories） | 本体无自动写入 | 后台异步：会话闲够久（~6h）、非短命、配额>25% 才生成；`/memories` 会话级控 |
| 更新机制 | 查重改而非增；写时自动打 `modified` ISO 时间戳；MEMORY.md 超 200 行/25KB 回压要求重写索引 | 无（改文件即改记忆，`/init` 增量改进 AGENTS.md） | 后台 `consolidation_model` 全局合并；密钥自动脱敏 |
| 遗忘机制 | 模型/用户主动删文件 + 删索引；记忆文件不受 cleanupPeriodDays 清理 | 无自动遗忘，手动删文件 | 无自动遗忘；Chronicle 记忆可删/改对应 md（但别手动加） |
| 开关 | `autoMemoryEnabled` / `CLAUDE_CODE_DISABLE_AUTO_MEMORY` / `autoMemoryDirectory` | `OPENCODE_DISABLE_CLAUDE_CODE*` 三个兼容开关 | `[features] memories`（默认 false，EEA/UK/瑞士不可用）+ `[memories]` 段旋钮 + `/memories` 会话级 |
| 扩展方式 | 分类文件 + `[[link]]` 关联 + `@path` 导入 + `.claude/rules` path-scoped | `opencode.json` 的 `instructions`（本地路径 / GLOB / `~/` / URL，URL 5s 超时）+ AGENTS.md 内写 `@file` 引导模型自己 read | AGENTS.md `project_doc_fallback_filenames` 备用名 + Skills + Chronicle（屏幕）+ MCP（`disable_on_external_context` 挡记忆） |
| 动态长期记忆 | 内建（Auto Memory，默认开） | 本体无，须外挂 MCP / 插件（如 opencode-supermemory，存云端 hybrid 语义库） | 内建（Memories，**默认关**、本地明文、后台异步）；Chronicle 屏幕喂记忆（实验性） |
| 会话 JSONL 日志（L2） | **transcript**：`~/.claude/projects/<project>/<session-id>.jsonl`，每行一条记录（type 字段区分 user/assistant/system/元数据），`uuid/parentUuid` 连链支持 rewind/fork；`--continue`/`--resume` 回放重建；受 `cleanupPeriodDays` 清理 | 本体不用 JSONL 存会话（SDK 存储 + 插件自建；跨会话积累全在云端插件侧） | **rollout**：`~/.codex/sessions/YYYY/MM/DD/rollout-*.jsonl` 追加全量历史（ZDR 无服务端态，本地即真相源），另建 `session_index.jsonl`/`state_5.sqlite` 索引层；`resume`/`fork` 回放；`--ephemeral` 不落盘 |

**核心差异**：
- Claude Code = **"索引常驻 + 正文按相关性召回"的双层自动记忆**，默认开、可依赖、鼓励手工读改删，模型自己写、自己维护索引、自己查重更新（`modified` 时间戳 + MEMORY.md 回压），是一套完整的"存/读/写/更新/遗忘"闭环。
- opencode = **"静态指令文件每步全量注入 System Prompt"的 Rules 机制**，本体完全不自动积累（存/读有，写/更新/遗忘几乎没有）；唯一的"动态"是 read 文件时就近把子目录 AGENTS.md 以 `<system-reminder>` 补进工具输出，并靠三重去重避免重复。想要真正的长期记忆得外挂 MCP / 插件，由插件自建整套记忆大脑。
- Codex = **"确定性指令（AGENTS.md）+ 概率性回忆（Memories）"双通路**，核心哲学是把二者严格分开——**必须每次生效的死规矩写 AGENTS.md（每轮必读、确定性），别赌 Memories 兜底**。Memories 默认关（EEA/UK/瑞士不可用）、后台异步生成（等会话闲够久、配额低就跳过、密钥脱敏）、存本地明文但定性为"生成态状态别手抠"；另有 Chronicle（仅 Pro+macOS，用屏幕内容喂记忆）与底层会话 rollout/compaction（L1/L2）。整体比 Claude Code 更保守、更强调"记忆是辅助回忆层而非规则来源"。

**三者一句话对照**：
- **默认开关**：Claude Code 自动记忆默认**开**；opencode 本体**无**自动记忆；Codex Memories 默认**关**（且有地区红线）。
- **指令文件重读时机**：opencode **每个 LLM step** 重读；Codex **每次运行/会话启动**读一次；Claude Code CLAUDE.md 启动全量注入 + 子目录按需。
- **会话日志落盘**：Claude Code（transcript）与 Codex（rollout）**不约而同选了 JSONL**——追加写 O(1)、流式回放、崩溃安全、`jq` 可审计，本质都是事件溯源（日志是唯一真相源，状态靠回放重建）；差异只在链结构与索引层。opencode 本体不走这条路。
- **可靠性定位**：Claude Code 把 Auto Memory 当可依赖的一等能力；Codex 明确 Memories 只是"概率性辅助回忆层"，死规矩必须落 AGENTS.md；opencode 干脆不做本体记忆。
