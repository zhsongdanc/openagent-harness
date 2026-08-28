package com.szh.tool;

/**
 * @author demussong
 * @describe
 * @date 2026/8/28 11:46
 */
public interface Tool {

    public String getCode();

    public ToolDefinition getToolDefinition();

    public String execute(String args);
}
