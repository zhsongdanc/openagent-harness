package com.szh.tool;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * @author demussong
 * @describe
 * @date 2026/8/25 17:34
 */
@Data
@Builder
public class ToolDefinition {
    private String name;
    private String code;
    private String description;
    // shell
    private String type;
    private String parameters;


}
