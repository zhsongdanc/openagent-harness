package com.szh.agent;

import com.szh.event.Event;
import com.szh.model.DeepSeekModel;
import com.szh.store.EventStore;
import com.szh.store.EventStoreFactory;
import com.szh.tool.ToolRegistry;
import com.szh.utils.CommonUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * @author demussong
 * @describe
 * @date 2026/8/27 21:23
 */
@Slf4j
public class AgentExecutor {

    public String run(String userInput) {
        return run("", userInput);
    }

    public String run(String sessionId, String userInput) {
        AgentRuntime agentRuntime = null;

        AgentState agentState = null;
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = CommonUtils.generateId();
            log.info("New session created: {}", sessionId);
            agentState = new AgentState(EventStoreFactory.createEventStore());
        } else if (sessionSaved(sessionId)) {
            agentState = recoverFromStore(sessionId);
        } else {
            throw new IllegalArgumentException("Session not found");
        }

        agentRuntime = new AgentRuntime(agentState, new ToolRegistry(), new DeepSeekModel(System.getenv("DEEPSEEK_API_KEY")));
        String res = agentRuntime.run(sessionId, userInput);
        agentRuntime.printLog(sessionId);
        return res;
    }

    public boolean sessionSaved(String sessionId) {
        // 先MOCK
        return false;
    }

    // TODO 先mock
    public AgentState recoverFromStore(String sessionId) {
        return new AgentState(EventStoreFactory.createEventStore());
    }
}
