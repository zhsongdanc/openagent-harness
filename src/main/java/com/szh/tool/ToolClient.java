package com.szh.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.szh.tool.dto.Location;

import java.util.List;

/**
 * @author demussong
 * @describe
 * @date 2026/8/25 19:53
 */
public class ToolClient {


    public static String call(String methodCode, String args) {
        if (methodCode.equals("queryLocation")) {
            return queryLocation(args);
        } else if (methodCode.equals("queryWeather")) {
            return queryWeather(args);
        }
        return "未知方法";
    }



    public static String queryLocation(String args) {
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
    public static String queryWeather(String latlng) {
        ObjectMapper objectMapper = new ObjectMapper();
        Location location = null;
        try {
            location = objectMapper.readValue(latlng, Location.class);
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
