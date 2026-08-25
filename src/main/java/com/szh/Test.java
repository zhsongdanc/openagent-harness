package com.szh;

import com.szh.agent.AgentRuntime;
import com.szh.model.FakeModel;
import com.szh.tool.ToolRegistry;

/**
 * @author demussong
 * @describe
 * @date 2026/8/24 17:47
 */
public class Test {
    public static void main(String[] args) {
        String userInput = "hello";

        AgentRuntime agentRuntime = new AgentRuntime(new ToolRegistry(), new FakeModel());
        String reply = agentRuntime.run(userInput);

        System.out.println(reply);
    }
}
