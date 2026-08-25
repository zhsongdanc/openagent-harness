package com.szh.tool;

import java.util.List;

/**
 * @author demussong
 * @describe
 * @date 2026/8/25 12:09
 */
public class ToolRegistry {

    public String call(String code, List<Object> args) {
        return "天气晴朗";
    }

    public List<ToolDefinition> getTools() {
        return List.of(
                ToolDefinition.builder()
                        .name("tool1")
                        .code("code1")
                        .description("description1")
                        .type("type1")
                        .parameters("parameters1")
                        .output("output1")
                        .build()
        );
    }
}
