# 改代码时的同步清单（L1 按需记忆）

> 命中条件：**动手写/改任何 Java 代码前**扫一遍本文；尤其是新增工具、新增事件类型、改运行时的场景。

## 代码风格

- 包根为 `com.szh`；新增工具放到 `com.szh.tool.tools` 下并注册进 `ToolRegistry`。
- 新增依赖外部命令（如 `git`/`mvn`/`docker` 等非 coreutils）的工具时，覆写 `Tool.requiredBinaries()`
  声明其依赖命令，`ToolRegistry` 会按本机 PATH 自动按需注册、缺命令则不暴露给模型；纯内置能力 / coreutils 无需声明。
- 中文注释，解释「为什么」而非「是什么」；对外配置项走 `ConfigUtil` 统一读取（`-Dkey=value` 优先于文件）。
- 工具类描述里若存在「装了但当前任务用不到」的误用风险，按既有规范在 description 开头加 `【适用前提】`
  前缀做软引导，而不是引入动态选型/惰性加载。

## 新增「写入类」工具必须同步的四处

（`write_file`/`edit_file`/`delete_file`/`git_add`/`git_commit`/`git_push` 就是这个清单的来源）

1. `PlanModePolicy.MUTATING_CODES` —— PLAN 模式拒绝名单，漏了就能在规划模式里改仓库；
2. `VerifyGate.markDirty(toolCode)` 的脏工具清单 —— 漏了改了代码却不触发验证闭环；
3. 若依赖外部命令：`Tool.requiredBinaries()` 声明，交给 `BinaryProbe` 过滤；
4. 若属于 `dispatch_subagent` 的只读类型白名单之外：确认 `SubAgentType` 各类型的 `allowedCodes` 是否需要显式排除。

## 新增 `EventEnum` 必须同步的三处（编译器会强制）

`EventJsonCodec.deserialize`、`TraceReplay.render`、`TraceReplay` 的 HTML 渲染分支都是**穷尽 switch**，
不加 case 直接编译失败——这是刻意保留的对齐闸门，不要改成 default 分支绕过。
另需决定：该事件是否继承 `MessageEvent`（进不进 `modelContext`），判据见 `event-and-context.md`。

## 两条运行时的对称性检查

任何 run 级状态/闸门改动，逐项确认在 `AgentRuntime` 与 `AgentResponseRuntime` 都接了：
`VerifyGate` 建实例与收尾、`LoopGuard` 记录、`AgentModeStore` 每轮读取、
`PlanModePolicy`/`VerifyPolicy`/`LoopGuard.decorateContext` 三个软装饰、
`TokenUsageEvent` 落库、上下文快照写入、压缩检查点落库。

## 冒烟测试传统

本项目**不写 JUnit**，验证一律走 `com.szh.test.*SmokeTest`（`main` 直跑，不依赖真实模型 API / MySQL / stdin，
需要模型时注入 Fake）。既有：`P1SmokeTest`、`MetaSmokeTest`、`VerifySmokeTest`、`SubAgentSmokeTest`、
`McpSmokeTest`、`CompactionCheckpointSmokeTest`、`WebViewerSmokeTest`、`InstructionMemorySmokeTest` 等。
约定：改动哪块就跑哪块 smoke；新增能力补断言；断言数写在类注释与对应 L1 文档的「冒烟」小节里。

## 构建与验证

```bash
export JAVA_HOME=/Users/demus/Library/Java/JavaVirtualMachines/ms-21.0.12.1-1/Contents/Home
mvn -q compile          # 编译闸门（改完必跑）
mvn -q clean package    # 产出 target/openagent.jar
```

验证闭环：本仓库 `application.properties` 里 `agent.verify.enabled=true`，但**验证命令清单只能由 json 提供**，
没有命令文件时闸门仍为「未武装」。想开启「改完自动跑编译/测试」，建
`{workspace}/.openagent/verify.json` 写明 commands（例：`mvn -q -DskipTests compile`、`mvn -q test`，
详见 `verify-gate.md`），之后 REPL `/verify` 可随手跑一遍、banner 会显示 `verify=on(N 命令)`。
