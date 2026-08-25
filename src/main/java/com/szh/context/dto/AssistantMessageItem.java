package com.szh.context.dto;

import com.szh.model.dto.ROLEEnum;
import lombok.Data;

/**
 * @author demussong
 * @describe
 * @date 2026/8/25 13:51
 */
@Data
public class AssistantMessageItem implements MessageItem {

    private boolean callTool;
    private String toolCode;
    private String toolArguments;
    private String content;


    public AssistantMessageItem(String content) {
        this.content = content;
    }

    public AssistantMessageItem(String toolCode, String toolArguments) {
        this.callTool = true;
        this.toolCode = toolCode;
        this.toolArguments = toolArguments;
    }


    @Override
    public String role() {
        return ROLEEnum.ASSISTANT.getRole();
    }

    @Override
    public String transfer2prompt() {
        if (callTool) {
            return "现在需要调用工具" + toolCode + "参数：" + toolArguments;
        } else {
            return content;
        }

    }
}
