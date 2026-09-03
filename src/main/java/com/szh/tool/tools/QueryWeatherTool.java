package com.szh.tool.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.szh.tool.FunctionTool;
import com.szh.tool.ToolContext;
import com.szh.tool.ToolDefinition;
import com.szh.tool.dto.Location;
import com.szh.tool.tools.local.LocalTool;

/**
 * @author demussong
 * @describe
 * @date 2026/8/28 12:23
 */
public class QueryWeatherTool implements LocalTool {

    public QueryWeatherTool() {}

    @Override
    public String getCode() {
        return "queryWeather";
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name("queryWeather")
                .code("queryWeather")
                .type("local")
                .description("根据用户经度和维度查询用户当前天气，返回字符串格式的天气信息")
                .parameters("{\"type\":\"object\",\"properties\":{\"longitude\":{\"type\":\"number\",\"description\":\"用户经度\"},\"latitude\":{\"type\":\"number\",\"description\":\"用户纬度\"}},\"required\":[\"longitude\",\"latitude\"]}")
                .build();

    }

    @Override
    public String execute(ToolContext toolContext) {
        ObjectMapper objectMapper = new ObjectMapper();
        Location location = null;
        try {
            location = objectMapper.readValue(toolContext.getArgs(), Location.class);
        } catch (JsonProcessingException e) {
            e.printStackTrace();
        }

        if (location != null && 50.0f == location.getLatitude()) {
            System.out.println("天气查询成功");
            return "晴朗";
        }
        return "参数不合理";
    }

}
