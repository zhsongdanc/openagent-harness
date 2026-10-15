---
memory-bound: pom.xml,src/main/java/com/szh/agent/AgentState.java
---
# AGENTS.md（本项目 L0 常驻指令记忆）

openagent-harness：用 Java 从零实现的 Agent Harness（智能体运行框架），核心是 while 循环的
Agent Loop：`model.call() → tool.execute() → 结果反馈给模型`，直到模型给出最终答案。

## 本文件的规矩（改这里之前先看）

本文件会**每轮每次模型调用都装进 system prompt**，属于最贵的常驻预算；模块细节全部下钻到
`docs/agents/*.md`（L1，**不常驻**，命中下方路由表时才用 `read_file` 取）。
因此往本文件加内容前的唯一判据是：**这条能否对应一次可预期的错误避免**；属于"某个模块怎么回事/
配置怎么用/冒烟测了什么"的一律写进 L1，不要塞进来。分层机制本身见 `docs/agents/instruction-memory.md`。

## 构建

- JDK 21（`maven.compiler.source/target=21`），Maven 构建；本机默认 `JAVA_HOME` 可能是 JDK8，
  编译/运行前必须切：`export JAVA_HOME=/Users/demus/Library/Java/JavaVirtualMachines/ms-21.0.12.1-1/Contents/Home`
- 编译验证：`mvn -q compile`（改完必跑）；打包 `mvn -q clean package` → `target/openagent.jar`
- 技术栈：Jackson、Log4j2、MyBatis-Plus + HikariCP + MySQL、Lombok；验证走 `com.szh.test.*SmokeTest`（main 直跑，**不写 JUnit**）

## 铁律（跨模块、违反即坏，只有这些常驻）

1. **事件日志是唯一真相源**：状态变更先 `applyEvent` 落库，运行期增量维护 `modelContext`，
   断点恢复由 `AgentState.resume()` 从事件流重建——不要在别处旁路维护状态。
2. **两套运行时都要改**：`AgentRuntime`/`AgentExecutor`（Chat Completions）与
   `AgentResponseRuntime`/`AgentResponseExecutor`（Responses API）；任何 run 级闸门漏接一处就是行为不一致。
3. **DeepSeek Responses API 分组约束**：同一轮内所有 `function_call` 必须连续排列、
   `function_call_output` 统一跟在其后，交错直接 400。
4. **surface 事件刻意不继承 `MessageEvent`**（不进 `modelContext`）；新增 `EventEnum` 必须同时补齐
   `EventJsonCodec.deserialize` 与 `TraceReplay` 的 describe/HTML 三处穷尽 switch（编译器会报错，别加 default 绕）。
5. **路径判断一律按规范化真实路径**：macOS `/tmp`→`/private/tmp`、`/var/folders`→`/private/var/folders`
   是符号链接，`PathGuard.canonical()`/`SandboxPolicy.real()` 必须 `toRealPath()`，否则误判越界；
   读边界默认用 `workspaceRoot`，**不能拿 `writableRoots` 当读校验边界**。
6. **诊断能力不许拖垮 run**：落库/落盘/快照/记忆类旁路逻辑一律 try-catch 静默降级，绝不抛异常。
7. **软引导写在工具 description 里**（`【适用前提】` 前缀），不做 run 起始动态选型、不引入惰性加载。
8. **别破坏 system prompt 前缀稳定性**：运行期软装饰只能往尾部 append；`InstructionMemoryLoader`
   装配出的分层记忆段是 DeepSeek prompt cache 的前缀，改写前部或频繁改动本文件都会让 cache 失效。

## L1 按需检索（命中就先 `read_file`，别凭记忆猜）

| 你要动什么 / 在排查什么 | 先读 |
| --- | --- |
| 事件、EventStore、AgentState、resume、上下文压缩、工具结果外存 | `docs/agents/event-and-context.md` |
| shell 权限闸门、Seatbelt 沙箱、模型重试、LoopGuard 熔断 | `docs/agents/security-and-reliability.md` |
| VerifyGate（`agent.verify.*`、verify.json、失败回灌、`/verify`） | `docs/agents/verify-gate.md` |
| 模型/provider/流式、文件工具、Git 工具、并行工具、BinaryProbe | `docs/agents/tools-and-models.md` |
| 元工具（todo/mode/ask_user_question、PLAN 模式与拒绝名单） | `docs/agents/meta-tools.md` |
| 子 agent / `dispatch_subagent` / 事件隔离 / 深度闸门 | `docs/agents/subagent.md` |
| MCP client（Server 起不来、stdio 卡死、工具命名空间） | `docs/agents/mcp.md` |
| REPL、TraceReplay、TokenUsageEvent、上下文快照、Web Viewer | `docs/agents/observability.md` |
| 指令记忆装配本身（L0/L1/L2、`memory.instruction.*`） | `docs/agents/instruction-memory.md` |
| **写任何 Java 代码前**（同步清单：新增写入工具四处 / 新增事件三处） | `docs/agents/contributing.md` |

## 代码风格

- 包根 `com.szh`；新增工具放 `com.szh.tool.tools` 下并注册进 `ToolRegistry`；
  依赖外部命令的工具覆写 `Tool.requiredBinaries()`（coreutils 不声明）。
- 中文注释，解释"为什么"而非"是什么"；对外配置项统一走 `ConfigUtil`（`-Dkey=value` 优先于配置文件）。
