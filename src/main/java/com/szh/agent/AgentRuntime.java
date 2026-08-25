package com.szh.agent;

import com.szh.context.ContextBuilder;
import com.szh.context.dto.*;
import com.szh.model.FakeModel;
import com.szh.model.Model;
import com.szh.model.dto.ActionEnum;
import com.szh.model.dto.ModelResp;
import com.szh.tool.ToolRegistry;

import java.util.ArrayList;

/**
 * @author demussong
 * @describe
 * @date 2026/8/25 12:09
 */
public class AgentRuntime {

    private ToolRegistry toolRegistry;

    private AgentState agentState = new AgentState();

    private Model model;

    public AgentRuntime(ToolRegistry toolRegistry, Model model) {
        this.toolRegistry = toolRegistry;
        this.model = model;
    }

    public static int MAX_ROUND = 100;

    public String run(String userInput) {

        MessageItem messageItem = new UserMessageItem(userInput);
        agentState.appendMsg(messageItem);


        int round = 0;
        while (true) {
            if (round > MAX_ROUND) {
                break;
            }

            String context = ContextBuilder.buildContext(agentState);
            // TODO 这里需要记录callId，以便后续记录调用工具进行关联
            ModelResp modelResp = model.call(new ArrayList<>(agentState.getHistories()), toolRegistry.getTools());
            agentState.appendMsg(modelResp.getMessage());

            if (modelResp.getAction() == ActionEnum.FINAL_ANSWER) {
                return modelResp.getMessage().getContent();
            }

            if (modelResp.getAction() == ActionEnum.TOOL_CALL) {
                String toolRes = toolRegistry.call(modelResp.getMessage().getToolCode(), new ArrayList<>());
                MessageItem toolMsg = new ToolMessageItem("123", modelResp.getMessage().getToolCode(), toolRes);
                agentState.appendMsg(toolMsg);
            }

        }
        return "reach max round";

    }
}
