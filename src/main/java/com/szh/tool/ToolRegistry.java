package com.szh.tool;

import java.util.List;

/**
 * @author demussong
 * @describe
 * @date 2026/8/25 12:09
 */
public class ToolRegistry {

    private List<ToolDefinition> tools;

    public ToolRegistry() {
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

        tools = List.of(queryLocation, queryWeather);
    }


    public List<ToolDefinition> getTools() {
        return tools;
    }
}
