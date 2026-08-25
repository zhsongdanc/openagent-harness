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

        testModel();
    }

    public static void testAgentRuntime() {
        String userInput = "hello";

        AgentRuntime agentRuntime = new AgentRuntime(new ToolRegistry(), new FakeModel());
        String reply = agentRuntime.run(userInput);
        System.out.println(reply);


        String reply2 = agentRuntime.run(userInput);
        System.out.println(reply);
    }

    public static void testModel() {
        DeepSeekModel model = new DeepSeekModel("");
        ToolDefinition queryLocation = ToolDefinition.builder()
                .name("queryLocation")
                .code("queryLocation")
                .description("根据用户ip查询用户当前位置，返回json格式的经度和维度")
                .parameters("{\"type\":\"object\",\"properties\":{\"ip\":{\"type\":\"string\",\"description\":\"字符串类型的用户ip\"}},\"required\":[\"ip\"]}")
                .build();
        ToolDefinition queryWeather = ToolDefinition.builder()
                .name("queryWeather")
                .code("queryWeather")
                .description("根据用户经度和维度查询用户当前天气，返回字符串格式的天气信息")
                .parameters("{\"type\":\"object\",\"properties\":{\"longitude\":{\"type\":\"number\",\"description\":\"用户经度\"},\"latitude\":{\"type\":\"number\",\"description\":\"用户纬度\"}},\"required\":[\"longitude\",\"latitude\"]}")
                .build();


        ModelResp modelResp = model.call(List.of(new UserMessageItem("我的ip是10.13.12.15,帮我查询一下当前天气")),
                List.of(queryLocation, queryWeather));
        System.out.println(modelResp.getMessage().getContent());
    }
}
