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

    /**
     * 会话是否已持久化：委托当前存储引擎的存在性检查（FILE/MYSQL 读盘/库，MEMORY 新实例恒为空）
     */
    public boolean sessionSaved(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return false;
        }
        return EventStoreFactory.createEventStore().exists(sessionId);
    }

    /**
     * 断点恢复：新建 AgentState 后从事件日志重建上下文（事件流是唯一真相源）
     */
    public AgentState recoverFromStore(String sessionId) {
        AgentState agentState = new AgentState(EventStoreFactory.createEventStore());
        agentState.resume(sessionId);
        return agentState;
    }
}
