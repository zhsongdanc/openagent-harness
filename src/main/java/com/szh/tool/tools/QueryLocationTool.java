package com.szh.tool.tools;

/**
 * @author demussong
 * @describe
 * @date 2026/8/28 12:28
 */
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.szh.tool.Tool;
import com.szh.tool.ToolDefinition;
import com.szh.tool.dto.Location;

public class QueryLocationTool implements Tool {

    private String code;
    private ToolDefinition toolDefinition;
    public QueryLocationTool(String code, ToolDefinition toolDefinition) {
        this.code = code;
        this.toolDefinition = toolDefinition;
    }
    @Override
    public String getCode() {
        return code;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return toolDefinition;
    }

    @Override
    public String execute(String args) {
        ObjectMapper objectMapper = new ObjectMapper();
        String ip = null;
        try {
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(args);
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
