package com.szh;

import com.szh.agent.AgentRuntime;
import com.szh.context.dto.AssistantMessageItem;
import com.szh.context.dto.MessageItem;
import com.szh.context.dto.UserMessageItem;
import com.szh.model.DeepSeekModel;
import com.szh.model.FakeModel;
import com.szh.model.dto.ModelResp;
import com.szh.tool.ToolDefinition;
import com.szh.tool.ToolRegistry;

import java.util.List;

/**
 * @author demussong
 * @describe
 * @date 2026/8/24 17:47
 */
public class Test {
    public static void main(String[] args) {

        testAgentRuntime();
    }

    public static void testAgentRuntime() {
        String userInput = "我的ip是10.13.12.15,帮我查询一下当前天气";

        AgentRuntime agentRuntime = new AgentRuntime(new ToolRegistry(), new DeepSeekModel(System.getenv("DEEPSEEK_API_KEY")));
        String reply = agentRuntime.run(userInput);
        System.out.println(reply);
    }

    public static void testModel() {
        DeepSeekModel model = new DeepSeekModel(System.getenv("DEEPSEEK_API_KEY"));

        ToolRegistry toolRegistry = new ToolRegistry();

        ModelResp modelResp = model.call(List.of(new UserMessageItem("我的ip是10.13.12.15,帮我查询一下当前天气")),
                toolRegistry.getTools());
        System.out.println(modelResp.getMessage().getContent());
    }

    public static void testApiKey() {
        System.out.println(System.getenv("DEEPSEEK_API_KEY"));
    }
}
