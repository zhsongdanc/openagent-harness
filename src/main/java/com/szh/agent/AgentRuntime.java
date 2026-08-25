package com.szh.agent;

import com.szh.context.dto.*;
import com.szh.model.FakeModel;
import com.szh.tool.ToolRegistry;

import java.util.ArrayList;

/**
 * @author demussong
 * @describe
 * @date 2026/8/25 12:09
 */
public class AgentRuntime {

    public static int MAX_ROUND = 100;

    public String run(String userInput) {

        AgentState agentState = new AgentState();
        MessageItem messageItem = new UserMessageItem(userInput);
        agentState.appendMsg(messageItem);


        int round = 0;
        while (true) {
            if (round > MAX_ROUND) {
                break;
            }

            String context = agentState.buildContext();
            FakeModel model = new FakeModel();
            AssistantMessageItem assistantMsg = model.call(context);
            agentState.appendMsg(assistantMsg);

            if (assistantMsg.isEnd()) {
                return assistantMsg.getContent();
            }

            if (assistantMsg.isCallTool()) {
                String toolRes = ToolRegistry.call(assistantMsg.getToolCode(), new ArrayList<>());
                MessageItem toolMsg = new ToolMessageItem(assistantMsg.getToolCode(), toolRes);
                agentState.appendMsg(toolMsg);
            }

        }
        return "reach max round";

    }
}
