
package com.szh.agent.handler;

import com.szh.context.dto.AssistantMessageItem;
import com.szh.context.dto.MessageItem;
import com.szh.context.dto.ToolMessageItem;
import com.szh.event.CallToolFinishedEvent;
import com.szh.event.CallToolStartedEvent;
import com.szh.event.ModelResponseEvent;
import com.szh.model.dto.output.FunctionCallOutputItem;
import com.szh.model.dto.output.OutputItem;
import com.szh.tool.Tool;
import com.szh.tool.ToolContext;
import lombok.extern.slf4j.Slf4j;

/**
 * @author demussong
 * @describe 工具调用输出项处理器：执行工具并记录事件，工具结果进历史供下轮回传
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

        context.getAgentState().applyEvent(new CallToolStartedEvent(
                context.getSessionId(), context.getRunId(), context.getTurnId(), context.getRound(),
                functionCall.getName(), functionCall.getArguments()));

        String toolRes;
        boolean threw = false;
        Tool tool = context.getToolRegistry().getToolByCode(functionCall.getName());
        if (tool == null) {
            log.warn("tool not found: {}", functionCall.getName());
            toolRes = "tool not found: " + functionCall.getName();
        } else {
            // 工具执行兜底：异常不再让整个 run 崩溃，而是转成错误结果并计入熔断统计
            try {
                toolRes = tool.execute(new ToolContext(
                        context.getSessionId(), context.getRunId(), context.getWorkspace(), functionCall.getArguments()));
            } catch (Exception e) {
                threw = true;
                log.error("tool execute failed: {}", functionCall.getName(), e);
                toolRes = "execute failed: " + e.getMessage();
            }
        }
        if (context.getLoopGuard() != null) {
            context.getLoopGuard().record(functionCall.getName(), functionCall.getArguments(), toolRes, threw);
        }

        // 元工具（inlineResult=true，如 read_tool_result）输出直接内联回传，避免二次落盘导致无限套娃；
        // 普通工具走落盘策略：以 callId 作为 resultId，超阈值才落盘并回传引用存根，小输出直接内联。
        String toolMsgContent;
        if (tool == null || tool.inlineResult()) {
            toolMsgContent = toolRes;
        } else {
            toolMsgContent = context.getToolResultStore()
                    .presentResult(functionCall.getCallId(), functionCall.getName(), toolRes);
        }

        MessageItem toolMsg = new ToolMessageItem(
                functionCall.getCallId(), functionCall.getName(), toolMsgContent);
        context.getAgentState().applyEvent(new CallToolFinishedEvent(
                context.getSessionId(), context.getRunId(), context.getTurnId(), context.getRound(), toolMsg,
                functionCall.getName(), toolRes));

        return HandleResult.toolCall();
    }
}
