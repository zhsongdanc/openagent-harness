package com.szh.tool.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.szh.tool.FunctionTool;
import com.szh.tool.ToolDefinition;
import com.szh.tool.dto.Location;

/**
 * @author demussong
 * @describe
 * @date 2026/8/28 12:23
 */
public class QueryWeatherTool implements FunctionTool {

    private String code;
    private ToolDefinition toolDefinition;

    public QueryWeatherTool(String code, ToolDefinition toolDefinition) {
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
        Location location = null;
        try {
            location = objectMapper.readValue(args, Location.class);
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
