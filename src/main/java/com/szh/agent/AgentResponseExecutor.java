
package com.szh.agent;

import com.szh.model.DeepSeekResponseModel;
import com.szh.store.EventStoreFactory;
import com.szh.tool.ToolRegistry;
import com.szh.utils.CommonUtils;
import lombok.extern.slf4j.Slf4j;

/**
 * @author demussong
 * @describe Responses API 版执行入口
 * @date 2026/8/31
 */
@Slf4j
public class AgentResponseExecutor {

    public String runNewSession(String userInput) {
        String sessionId = CommonUtils.generateId();
        log.info("New session created: {}", sessionId);
        AgentState agentState = new AgentState(EventStoreFactory.createEventStore());
        return run(sessionId, userInput, agentState);
    }

    public String runExistsSession(String sessionId, String userInput) {
        log.info("Resume session: {}", sessionId);
        AgentState agentState = new AgentState(EventStoreFactory.createEventStore());
        agentState.resume(sessionId);
        return run(sessionId, userInput, agentState);
    }

    public String run(String sessionId, String userInput, AgentState agentState) {
        AgentResponseRuntime agentRuntime = new AgentResponseRuntime(agentState, new ToolRegistry(),
                new DeepSeekResponseModel(System.getenv("DEEPSEEK_API_KEY")));
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
