package com.szh.tool.tools;

/**
 * @author demussong
 * @describe
 * @date 2026/8/28 12:28
 */
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.szh.tool.Tool;
import com.szh.tool.ToolContext;
import com.szh.tool.ToolDefinition;
import com.szh.tool.dto.Location;
import com.szh.tool.tools.local.LocalTool;

public class QueryLocationTool implements LocalTool {


    public QueryLocationTool() {}
    @Override
    public String getCode() {
        return "queryLocation";
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name("queryLocation")
                .code("queryLocation")
                .description("根据用户ip查询用户当前位置，返回json格式的经度和维度")
                .parameters("{\"type\":\"object\",\"properties\":{\"ip\":{\"type\":\"string\",\"description\":\"字符串类型的用户ip\"}},\"required\":[\"ip\"]}")
                .build();
    }

    @Override
    public String execute(ToolContext toolContext) {
        ObjectMapper objectMapper = new ObjectMapper();
        String ip = null;
        try {
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(toolContext.getArgs());
            ip = node.has("ip") ? node.get("ip").asText() : null;
        } catch (JsonProcessingException e) {
            e.printStackTrace();
        }
        if (ip == null || ip.length() == 0) {
            return "IP不能为空";
        }


        float lat = 50.0f;
        float lng = 50.0f;
        Location location = new Location(lat, lng);
        String json = "";
        try {
            json = objectMapper.writeValueAsString(location);
        } catch (JsonProcessingException e) {
            e.printStackTrace();
        }
        return json;
    }
}
