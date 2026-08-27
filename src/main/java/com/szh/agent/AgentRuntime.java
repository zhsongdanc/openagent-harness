package com.szh.agent;

import com.szh.context.ContextBuilder;
import com.szh.context.dto.*;
import com.szh.event.*;
import com.szh.model.Model;
import com.szh.model.dto.ActionEnum;
import com.szh.model.dto.ModelResp;
import com.szh.store.MemoryEventStore;
import com.szh.tool.ToolClient;
import com.szh.tool.ToolRegistry;
import org.apache.commons.collections4.CollectionUtils;

import java.util.ArrayList;

/**
 * @author demussong
 * @describe
 * @date 2026/8/25 12:09
 */
public class AgentRuntime {

    private ToolRegistry toolRegistry;

    private AgentState agentState = new AgentState(new MemoryEventStore());

    private Model model;

    public AgentRuntime(AgentState agentState, ToolRegistry toolRegistry, Model model) {
        this.toolRegistry = toolRegistry;
        this.model = model;
    }

    public static int MAX_ROUND = 100;

    public String run(String sessionId, String userInput) {
        agentState.incrementTurnId();

        agentState.applyEvent(new RunStartedEvent());

        MessageItem messageItem = new UserMessageItem(userInput);
        UserMessageEvent userMessageEvent = new UserMessageEvent(messageItem, userInput);
        agentState.applyEvent(userMessageEvent);

        String res = "";
        int round = 0;
        while (true) {
            if (round > MAX_ROUND) {
                break;
            }

            String context = ContextBuilder.buildContext(agentState);
            // TODO 这里需要记录callId，以便后续记录调用工具进行关联
            ModelResp modelResp = model.call(new ArrayList<>(agentState.getHistories()), toolRegistry.getTools());
            ModelResponseEvent modelResponseEvent = new ModelResponseEvent(modelResp.getMessage(), "");
            agentState.applyEvent(modelResponseEvent);

            if (modelResp.getAction() == ActionEnum.FINAL_ANSWER) {
                res =  modelResp.getMessage().getContent();
                break;
            }

            if (modelResp.getAction() == ActionEnum.TOOL_CALL) {
                AssistantMessageItem toolMessage = modelResp.getMessage();
                CallToolStartedEvent callToolStartedEvent = new CallToolStartedEvent(toolMessage.getToolCode(), toolMessage.getToolArgs());
                agentState.applyEvent(callToolStartedEvent);
                String args = toolMessage.getToolArgs();
                String toolRes = ToolClient.call(toolMessage.getToolCode(), args);


                String toolCallId = toolMessage.getToolCallId();
                MessageItem toolMsg = new ToolMessageItem(toolCallId, toolMessage.getToolCode(), toolRes);
                CallToolFinishedEvent callToolFinishedEvent = new CallToolFinishedEvent(toolMsg, "",toolMessage.getToolCode(), toolRes);
                agentState.applyEvent(callToolFinishedEvent);
            }

        }

        if (round > MAX_ROUND) {
            res = "reach max round";
        } else if (res == "") {
            res = "unknown error";
        }
        agentState.applyEvent(new RunCompletedEvent("",  res));
        return res;

    }

    public boolean isNew() {
        return CollectionUtils.isEmpty(agentState.getHistories());
    }

    public void recoverState(AgentState agentState) {
        this.agentState = agentState; // Recovering the agent state
    }
}
