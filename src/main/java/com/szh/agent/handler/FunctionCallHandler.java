
package com.szh.agent.handler;

import com.szh.context.dto.AssistantMessageItem;
import com.szh.event.ModelResponseEvent;
import com.szh.model.dto.ToolCall;
import com.szh.model.dto.output.FunctionCallOutputItem;
import com.szh.model.dto.output.OutputItem;
import lombok.extern.slf4j.Slf4j;

/**
 * @author demussong
 * @describe 工具调用输出项处理器：只负责落 ModelResponseEvent（进历史供下轮回传）并收集调用，
 * 不再就地执行工具。
 * <p>
 * 为什么拆成「收集 + 批量执行」：一轮内模型可能并行请求多个 function_call，
 * 由 AgentResponseRuntime 收齐后交给 ParallelToolExecutor 并发执行，
 * 执行事件按「全部 started → 并发执行 → 全部 finished」有序落库，
 * 同时满足 DeepSeek Responses API 的 function_call 连续分组约束。
 * @date 2026/8/31
 */
@Slf4j
public class FunctionCallHandler implements OutputItemHandler {

    @Override
    public String supportType() {
        return FunctionCallOutputItem.TYPE;
    }

    @Override
    public HandleResult handle(OutputItem item, HandleContext context) {
        FunctionCallOutputItem functionCall = (FunctionCallOutputItem) item;

        AssistantMessageItem toolCallMessage = new AssistantMessageItem(
                functionCall.getCallId(), functionCall.getName(), functionCall.getArguments());
        context.getAgentState().applyEvent(new ModelResponseEvent(
                context.getSessionId(), context.getRunId(), context.getTurnId(), context.getRound(), toolCallMessage));

        return HandleResult.toolCall(
                new ToolCall(functionCall.getCallId(), functionCall.getName(), functionCall.getArguments()));
    }
}
