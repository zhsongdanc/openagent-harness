
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
import com.szh.tool.store.ToolResultStore;
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
        Tool tool = context.getToolRegistry().getToolByCode(functionCall.getName());
        if (tool == null) {
            log.warn("tool not found: {}", functionCall.getName());
            toolRes = "tool not found: " + functionCall.getName();
        } else {
            toolRes = tool.execute(new ToolContext(
                    context.getSessionId(), context.getRunId(), context.getWorkspace(), functionCall.getArguments()));
        }

        // 工具完整输出存入文件，上下文中只保留引用
        ToolResultStore store = new ToolResultStore(context.getWorkspace(), context.getSessionId());
        String resultId = store.store(functionCall.getName(), toolRes);
        int lineCount = toolRes == null ? 0 : toolRes.split("\n", -1).length;
        String reference = "结果已存储[result_id=" + resultId + ", lines=" + lineCount
                + "]，使用 read_tool_result 工具查阅详情";

        MessageItem toolMsg = new ToolMessageItem(
                functionCall.getCallId(), functionCall.getName(), reference);
        context.getAgentState().applyEvent(new CallToolFinishedEvent(
                context.getSessionId(), context.getRunId(), context.getTurnId(), context.getRound(), toolMsg,
                functionCall.getName(), toolRes));

        return HandleResult.toolCall();
    }
}
