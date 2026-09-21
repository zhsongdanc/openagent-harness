
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
        if (!sessionSaved(sessionId)) {
            throw new IllegalArgumentException("Session not found: " + sessionId);
        }
        log.info("Resume session: {}", sessionId);
        AgentState agentState = recoverFromStore(sessionId);
        return run(sessionId, userInput, agentState);
    }

    public String run(String sessionId, String userInput, AgentState agentState) {
        AgentResponseRuntime agentRuntime = new AgentResponseRuntime(agentState, new ToolRegistry(),
                new DeepSeekResponseModel(System.getenv("DEEPSEEK_API_KEY")));
        String res = agentRuntime.run(sessionId, userInput);
        agentRuntime.printLog(sessionId);
        return res;
    }


    /**
     * 会话是否已持久化：委托当前存储引擎的存在性检查
     */
    public boolean sessionSaved(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return false;
        }
        return EventStoreFactory.createEventStore().exists(sessionId);
    }

    /**
     * 断点恢复：新建 AgentState 后从事件日志重建上下文
     */
    public AgentState recoverFromStore(String sessionId) {
        AgentState agentState = new AgentState(EventStoreFactory.createEventStore());
        agentState.resume(sessionId);
        return agentState;
    }
}
