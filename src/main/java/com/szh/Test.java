package com.szh;

import com.szh.agent.AgentExecutor;
import com.szh.agent.AgentResponseExecutor;
import com.szh.agent.AgentRuntime;
import com.szh.context.dto.AssistantMessageItem;
import com.szh.context.dto.MessageItem;
import com.szh.context.dto.UserMessageItem;
import com.szh.model.DeepSeekModel;
import com.szh.model.DeepSeekResponseModel;
import com.szh.model.FakeModel;
import com.szh.model.Model;
import com.szh.model.dto.ModelResp;
import com.szh.model.dto.output.ResponseModelResp;
import com.szh.tool.ToolDefinition;
import com.szh.tool.ToolRegistry;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * @author demussong
 * @describe
 * @date 2026/8/24 17:47
 */

@Slf4j
public class Test {
    public static void main(String[] args) {

        testResponseAgent();
    }

    public static void testAgentRuntime() {
        String userInput = "我的ip是10.13.12.15,帮我查询一下当前天气";
        AgentExecutor agentExecutor = new AgentExecutor();
        String reply = agentExecutor.run(userInput);
        System.out.println(reply);
    }

    public static void testResponseAgent() {
        String userInput = "我的ip是10.13.12.15,帮我查询一下当前天气";
        AgentResponseExecutor agentExecutor = new AgentResponseExecutor();
        String reply = agentExecutor.run(userInput);
        System.out.println(reply);
    }

    public static void testModel() {
        DeepSeekResponseModel model = new DeepSeekResponseModel(System.getenv("DEEPSEEK_API_KEY"));

        ToolRegistry toolRegistry = new ToolRegistry();

        ResponseModelResp responseModelResp = model.call(List.of(new UserMessageItem("我的ip是10.13.12.15,帮我查询一下当前天气")),
                toolRegistry.getTools());
        System.out.println("success");
    }

    public static void testApiKey() {
        log.info("print log test");
        System.out.println(System.getenv("DEEPSEEK_API_KEY"));
    }
}
