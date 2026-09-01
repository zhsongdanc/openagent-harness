package com.szh.context;

import com.szh.agent.AgentState;
import com.szh.context.dto.MessageItem;

import java.util.List;

/**
 * @author demussong
 * @describe
 * @date 2026/8/25 16:20
 */
public class ContextBuilder {

    public static String buildContext(AgentState agentState) {

        List<MessageItem> histories = agentState.getModelContext();

        StringBuilder prompt = new StringBuilder();
        histories.forEach(messageItem -> {
            prompt.append(messageItem.transfer2prompt());
        });

        return prompt.toString();
    }
}
