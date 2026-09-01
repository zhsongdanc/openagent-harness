
package com.szh.agent;

import com.szh.model.DeepSeekResponseModel;
import com.szh.store.MemoryEventStore;
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

    public String run(String userInput) {
        return run("", userInput);
    }

    public String run(String sessionId, String userInput) {
        AgentState agentState;
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = CommonUtils.generateId();
            log.info("New session created: {}", sessionId);
            agentState = new AgentState(new MemoryEventStore());
        } else if (sessionSaved(sessionId)) {
            agentState = recoverFromStore(sessionId);
        } else {
            throw new IllegalArgumentException("Session not found");
        }

        AgentResponseRuntime agentRuntime = new AgentResponseRuntime(agentState, new ToolRegistry(),
                new DeepSeekResponseModel(System.getenv("DEEPSEEK_API_KEY")));
        String res = agentRuntime.run(sessionId, userInput);
        agentRuntime.printLog();
        return res;
    }

    public boolean sessionSaved(String sessionId) {
        // 先MOCK
        return false;
    }

    // TODO 先mock
    public AgentState recoverFromStore(String sessionId) {
        return new AgentState(new MemoryEventStore());
    }
}
