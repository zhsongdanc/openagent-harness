
package com.szh.agent.handler;

import com.szh.context.dto.AssistantMessageItem;
import com.szh.event.ModelResponseEvent;
import com.szh.model.dto.output.MessageOutputItem;
import com.szh.model.dto.output.OutputItem;

/**
 * @author demussong
 * @describe 文本回答输出项处理器：记录事件并返回文本内容
 * @date 2026/8/31
 */
public class MessageHandler implements OutputItemHandler {

    @Override
    public String supportType() {
        return MessageOutputItem.TYPE;
    }

    @Override
    public HandleResult handle(OutputItem item, HandleContext context) {
        MessageOutputItem message = (MessageOutputItem) item;
        context.getAgentState().applyEvent(new ModelResponseEvent(
                context.getSessionId(), context.getRunId(), context.getTurnId(), context.getRound(),
                new AssistantMessageItem(message.getContent())));
        return HandleResult.message(message.getContent());
    }
}
