
package com.szh.agent.handler;

import com.szh.context.dto.ReasoningMessageItem;
import com.szh.event.ReasoningEvent;
import com.szh.model.dto.output.OutputItem;
import com.szh.model.dto.output.ReasoningOutputItem;

/**
 * @author demussong
 * @describe 思维链输出项处理器：记录事件并进历史，供下轮回传
 * @date 2026/8/31
 */
public class ReasoningHandler implements OutputItemHandler {

    @Override
    public String supportType() {
        return ReasoningOutputItem.TYPE;
    }

    @Override
    public HandleResult handle(OutputItem item, HandleContext context) {
        ReasoningOutputItem reasoning = (ReasoningOutputItem) item;
        ReasoningMessageItem messageItem = new ReasoningMessageItem(reasoning.getContent());
        context.getAgentState().applyEvent(new ReasoningEvent(
                context.getSessionId(), context.getTurnId(), messageItem));
        return HandleResult.none();
    }
}
