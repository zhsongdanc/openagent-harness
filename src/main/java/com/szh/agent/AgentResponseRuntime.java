
package com.szh.agent;

import com.szh.agent.handler.HandleContext;
import com.szh.agent.handler.HandleResult;
import com.szh.agent.handler.OutputItemHandlerRegistry;
import com.szh.context.dto.UserMessageItem;
import com.szh.event.Event;
import com.szh.event.RunCompletedEvent;
import com.szh.event.RunStartedEvent;
import com.szh.event.UserMessageEvent;
import com.szh.model.ResponseModel;
import com.szh.model.dto.output.OutputItem;
import com.szh.model.dto.output.ResponseModelResp;
import com.szh.tool.ToolRegistry;
import com.szh.trace.RunTrace;
import com.szh.trace.StepTrace;
import com.szh.utils.CommonUtils;
import org.apache.commons.collections4.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * @author demussong
 * @describe 基于 Responses API 的 Agent 运行时：
 * 每轮模型输出可包含多个输出项（reasoning/message/function_call），
 * 逐个分发给对应处理器执行，一轮内的多个工具调用串行执行完毕后再进入下一轮
 * @date 2026/8/31
 */
public class AgentResponseRuntime {

    private static final Logger log = LoggerFactory.getLogger(AgentResponseRuntime.class);

    public static int MAX_ROUND = 100;

    private final ToolRegistry toolRegistry;

    private final AgentState agentState;

    private final ResponseModel model;

    private final OutputItemHandlerRegistry handlerRegistry;

    public AgentResponseRuntime(AgentState agentState, ToolRegistry toolRegistry, ResponseModel model) {
        this.agentState = agentState;
        this.toolRegistry = toolRegistry;
        this.model = model;
        this.handlerRegistry = OutputItemHandlerRegistry.defaultRegistry();
    }

    public String run(String sessionId, String userInput) {
        String runId = CommonUtils.generateId();
        RunTrace runTrace = new RunTrace(runId, sessionId, System.currentTimeMillis());

        agentState.incrementTurnId();
        String turnId = getTurnName(agentState.getTurnId());

        agentState.applyEvent(new RunStartedEvent(sessionId, turnId));
        agentState.applyEvent(new UserMessageEvent(sessionId, turnId, new UserMessageItem(userInput), userInput));

        HandleContext handleContext = new HandleContext(agentState, toolRegistry, sessionId, turnId);

        String res = "";
        int round = 0;
        while (true) {
            if (round > MAX_ROUND) {
                break;
            }
            round++;
            long roundStart = System.currentTimeMillis();

            ResponseModelResp modelResp = model.call(
                    new ArrayList<>(agentState.getModelContext()), toolRegistry.getTools());

            boolean anyToolCall = false;
            String lastMessage = null;
            for (OutputItem item : modelResp.getItems()) {
                HandleResult result = handlerRegistry.dispatch(item, handleContext);
                if (result.isToolCallExecuted()) {
                    anyToolCall = true;
                }
                if (result.getMessageContent() != null) {
                    lastMessage = result.getMessageContent();
                }
            }

            StepTrace stepTrace = new StepTrace(round, roundStart, System.currentTimeMillis());
            runTrace.addStepTrace(stepTrace);

            if (!anyToolCall) {
                if (lastMessage != null) {
                    res = lastMessage;
                }
                break;
            }
        }

        if (round > MAX_ROUND) {
            res = "reach max round";
        } else if (Objects.equals(res, "")) {
            res = "unknown error";
        }
        agentState.applyEvent(new RunCompletedEvent(sessionId, res));
        runTrace.setEndTime(System.currentTimeMillis());
        runTrace.printTraceByRunId(runId);
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
