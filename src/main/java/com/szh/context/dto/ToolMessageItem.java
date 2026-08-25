package com.szh.context.dto;

/**
 * @author demussong
 * @describe
 * @date 2026/8/25 13:53
 */
public class ToolMessageItem implements MessageItem {

    private String toolCode;
    private String execResult;

    public ToolMessageItem(String toolCode, String execRes) {
        this.toolCode = toolCode;
        this.execResult = execRes;
    }

    @Override
    public String transfer2prompt() {
        return "已经执行工具code:" + toolCode + "，结果为：" + execResult;
    }
}
