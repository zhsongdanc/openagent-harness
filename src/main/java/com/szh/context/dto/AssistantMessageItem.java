package com.szh.context.dto;

import lombok.Data;

/**
 * @author demussong
 * @describe
 * @date 2026/8/25 13:51
 */
@Data
public class AssistantMessageItem implements MessageItem {

    private boolean end;
    private boolean callTool;
    private String toolCode;
    private String content;


    public AssistantMessageItem(boolean end, String content) {
        this.end = end;
        this.content = content;
    }

    public AssistantMessageItem(boolean end, boolean callTool, String toolCode, String content) {
        this.end = end;
        this.callTool = callTool;
        this.toolCode = toolCode;
        this.content = content;
    }



    @Override
    public String transfer2prompt() {
        if (callTool) {
            return "现在需要调用工具" + toolCode;
        } else {
            return content;
        }

    }
}
