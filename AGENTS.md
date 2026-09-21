# AGENTS.md

本文件是 openagent-harness 的**项目级指令记忆**，会在每次会话启动时由
`InstructionMemoryLoader` 自动装配进 system prompt（全局 `~/.openagent/AGENTS.md` →
仓库根 → 当前目录，逐级合并；支持 `@path` 引入其它文件）。

## 项目概述

openagent-harness 是一个用 Java 从零实现的 Agent Harness（智能体运行框架），
参考 Claude Code / Codex 等主流工程设计。核心是一个 while 循环的 Agent Loop：
`model.call() → tool.execute() → 结果反馈给模型`，直到模型给出最终答案。

## 技术栈与构建

- JDK 21（`maven.compiler.source/target=21`），构建工具 Maven。
- 本机默认 `JAVA_HOME` 可能是 JDK8，编译/运行前需切到 JDK21：
  `/Users/demus/Library/Java/JavaVirtualMachines/ms-21.0.12.1-1/Contents/Home`。
- 编译验证：`mvn -q compile`。
- 关键依赖：Jackson（序列化）、Log4j2（日志）、MyBatis-Plus + HikariCP + MySQL（事件落库）、Lombok。

## 架构约定（务必遵守）

- **事件日志是唯一真相源**：所有状态变更先 `applyEvent` 落库，运行期再增量维护
  `modelContext`；断点恢复由 `AgentState.resume()` 从事件流重建，不要在别处旁路维护状态。
- **两套运行时**：`AgentRuntime`/`AgentExecutor` 走 Chat Completions API（`Model`）；
  `AgentResponseRuntime`/`AgentResponseExecutor` 走 Responses API（`ResponseModel`）。改动需兼顾两者。
- **DeepSeek Responses API 分组约束**：同一轮内所有 `function_call` 必须连续排列、
  `function_call_output` 统一跟在其后，交错会触发 400。
- **上下文压缩**：单一阈值 `compaction.threshold`，超阈值先 L0 规则压缩、仍超再升级 L1 LLM 摘要。
- **工具结果外存**：超阈值的工具输出落盘到 `{workspace}/.agent-data/tool-results/{sessionId}/{callId}.txt`，
  上下文只保留引用存根，模型用 `read_tool_result` 分页读取；`read_tool_result` 自身输出必须豁免落盘（否则无限套娃）。

## 存储引擎

由 `store.engine` 切换：`MEMORY`（进程内、非持久）、`MYSQL`（落库）、
`FILE`（Codex 风格 JSONL rollout，落 `~/.openagent/sessions/{sessionId}.jsonl`）。

## 代码风格

- 包根为 `com.szh`；新增工具放到 `com.szh.tool.tools` 下并注册进 `ToolRegistry`。
- 中文注释，解释“为什么”而非“是什么”；对外配置项走 `ConfigUtil` 统一读取。
