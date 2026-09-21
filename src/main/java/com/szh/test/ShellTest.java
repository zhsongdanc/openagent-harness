package com.szh.test;

import com.szh.agent.AgentResponseExecutor;
import com.szh.agent.AgentResponseRuntime;
import com.szh.agent.AgentState;
import com.szh.context.compaction.StepCompactor;
import com.szh.context.dto.MessageItem;
import com.szh.context.dto.UserMessageItem;
import com.szh.model.DeepSeekResponseModel;
import com.szh.store.EventStoreFactory;
import com.szh.tool.ToolRegistry;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * @author demussong
 * @describe
 * @date 2026/9/3 10:49
 */
@Slf4j
public class ShellTest {
    public static void main(String[] args) {
//        testChatStream();
//        testShellTool();
        testCompactionTrigger();
    }

    /**
     * Chat Completions 链路端到端：验证 SSE 流式输出与并行 tool_calls 解析
     */
    public static void testChatStream() {
        String userInput = "请用 repo_map 工具查看项目结构，再用一句话总结这是个什么项目";
        com.szh.agent.AgentExecutor agentExecutor = new com.szh.agent.AgentExecutor();
        String reply = agentExecutor.run(userInput);
        System.out.println("=== 最终回答 ===");
        System.out.println(reply);
    }


    public static void testShellTool() {
        String userInput = "请用 repo_map 工具看一下当前项目的结构，并用一句话总结这是个什么项目";
        AgentResponseExecutor agentExecutor = new AgentResponseExecutor();
        String reply = agentExecutor.runNewSession(userInput);
        System.out.println(reply);
    }

    /**
     * 测试上下文压缩触发：
     * 向上下文中注入大量模拟历史消息，使估算 token 数超过 contextWindow 的 L0 阈值（70%），
     * 观察日志中 [Compaction] 前缀的压缩触发、L0/L1 执行、压缩前后对比等日志。
     * <p>
     * 预期日志关键标志：
     * - [Compaction] 触发压缩: ratio=0.xx >= 0.7, 消息数=X
     * - StepCompactor L0: messages X -> Y, tokens ~A -> ~B
     * - [Compaction] L0 完成: ratio 0.xx -> 0.yy, 消息数 X -> Y
     * - LlmCompactor L1: messages Y -> Z, tokens ~B -> ~C （仅 L0 不够时）
     * - [Compaction] L0+L1 完成: ratio ... -> ... -> ..., 消息数 X -> Y -> Z （仅 L0 不够时）
     * - [Round 1] >>> 压缩已执行: 消息数 X -> Z <<<
     */
    public static void testCompactionTrigger() {
        log.info("====== 测试压缩触发 ======");

        AgentState agentState = new AgentState(EventStoreFactory.createEventStore());

        // 注入模拟历史消息，使估算 token 数超过 contextWindow(64000) 的 70% 阈值。
        // 每条 5000 字符 > TOOL_RESULT_MAX_CHARS(2000)，L0 会截断至 2000 字符；
        // 80 条 × ~2000 tokens ≈ 160K tokens（截断后 ~64K），足以触发 L0 压缩。
        // 注意：消息数不宜过多（DeepSeek API 有消息数量限制），内容用纯填充文本避免误导模型。
        List<MessageItem> ctx = new ArrayList<>(agentState.getModelContext());
        int fakeMsgCount = 20;
        int charsPerMsg = 5000;
        for (int i = 0; i < fakeMsgCount; i++) {
            StringBuilder content = new StringBuilder();
            content.append("历史对话记录 #").append(i + 1).append("：");
            // 填充到目标字符数（纯填充文本，不包含伪造的工具调用/结果）
            while (content.length() < charsPerMsg) {
                content.append("这是用于测试上下文压缩机制的填充文本。");
            }
            ctx.add(new UserMessageItem(content.toString()));
        }
        agentState.replaceModelContext(ctx);

        int estimatedTokens = StepCompactor.estimateTotalTokens(agentState.getModelContext());
        log.info("已注入 {} 条模拟历史消息，估算总 tokens: {}", fakeMsgCount, estimatedTokens);
        log.info("上下文窗口: 64000, L0 阈值: 70% = 44800, 当前估算 ratio: {}",
                String.format("%.2f", (double) estimatedTokens / 64000));

        // 创建 AgentResponseRuntime 并执行，第一轮压缩检查应触发 L0
        AgentResponseRuntime runtime = new AgentResponseRuntime(
                agentState, new ToolRegistry(), new DeepSeekResponseModel(System.getenv("DEEPSEEK_API_KEY")));
        String reply = runtime.run("", "请告诉我当前项目的结构");
        System.out.println("=== 模型回复 ===");
        System.out.println(reply);
    }
}
