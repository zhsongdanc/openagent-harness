package com.szh.agent;

import com.szh.context.ContextBuilder;
import com.szh.context.dto.*;
import com.szh.event.*;
import com.szh.model.Model;
import com.szh.model.dto.ActionEnum;
import com.szh.model.dto.ModelResp;
import com.szh.store.MemoryEventStore;
import com.szh.tool.Tool;
import com.szh.tool.ToolRegistry;
import org.apache.commons.collections4.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * @author demussong
 * @describe
 * @date 2026/8/25 12:09
 */
public class AgentRuntime {

    private static final Logger log = LoggerFactory.getLogger(AgentRuntime.class);

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
        String turnId = getTurnName(agentState.getTurnId());

        agentState.applyEvent(new RunStartedEvent(sessionId, turnId));
        MessageItem messageItem = new UserMessageItem(userInput);
        UserMessageEvent userMessageEvent = new UserMessageEvent(sessionId, turnId, messageItem, userInput);
        agentState.applyEvent(userMessageEvent);

        String res = "";
        int round = 0;
        while (true) {
            if (round > MAX_ROUND) {
                break;
            }
            round++;

            String context = ContextBuilder.buildContext(agentState);
            // TODO 这里需要记录callId，以便后续记录调用工具进行关联
            ModelResp modelResp = model.call(new ArrayList<>(agentState.getHistories()), toolRegistry.getTools());
            ModelResponseEvent modelResponseEvent = new ModelResponseEvent(sessionId, turnId, modelResp.getMessage());
            agentState.applyEvent(modelResponseEvent);

            if (modelResp.getAction() == ActionEnum.FINAL_ANSWER) {
                res =  modelResp.getMessage().getContent();
                break;
            }

            if (modelResp.getAction() == ActionEnum.TOOL_CALL) {
                AssistantMessageItem toolMessage = modelResp.getMessage();
                CallToolStartedEvent callToolStartedEvent = new CallToolStartedEvent(sessionId, turnId,
                        toolMessage.getToolCode(), toolMessage.getToolArgs());
                agentState.applyEvent(callToolStartedEvent);
                String args = toolMessage.getToolArgs();
                Tool tool = toolRegistry.getToolByCode(toolMessage.getToolCode());
                String toolRes = tool.execute(args);


                String toolCallId = toolMessage.getToolCallId();
                MessageItem toolMsg = new ToolMessageItem(toolCallId, toolMessage.getToolCode(), toolRes);
                CallToolFinishedEvent callToolFinishedEvent = new CallToolFinishedEvent(sessionId, turnId, toolMsg,
                        toolMessage.getToolCode(), toolRes);
                agentState.applyEvent(callToolFinishedEvent);
            }

        }

        if (round > MAX_ROUND) {
            res = "reach max round";
        } else if (Objects.equals(res, "")) {
            res = "unknown error";
        }
        agentState.applyEvent(new RunCompletedEvent(sessionId,  res));
        return res;

    }

    private String getTurnName(int turnId) {
        return "turn_" + turnId;
    }

    public void printLog() {
        List<Event> events = agentState.getEventStore().getEvents();
        if (CollectionUtils.isEmpty(events)) {
            log.info("no events recorded");
            return;
        }

        List<Event> sorted = new ArrayList<>(events);
        sorted.sort(Comparator.comparing(Event::getTimestamp));

        log.info("===== Event Log (total: {}) =====", sorted.size());
        for (int i = 0; i < sorted.size(); i++) {
            Event event = sorted.get(i);
            log.info("[{}] {} | time: {} | sessionId: {} | turnId: {}",
                    i + 1,
                    event.getType(),
                    event.getTimestamp(),
                    event.getSessionId(),
                    event.getTurnId());
        }
        log.info("===== End Event Log =====");
    }
}
