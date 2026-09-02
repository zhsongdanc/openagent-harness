package com.szh.context.dto;

import com.szh.model.dto.ROLEEnum;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author demussong
 * @describe
 * @date 2026/8/25 13:51
 */
@Data
@NoArgsConstructor
public class AssistantMessageItem implements MessageItem {

    private boolean callTool;
    private String toolCallId;
    private String toolCode;
    private String toolArgs;
    private String content;


    public AssistantMessageItem(String content) {
        this.content = content;
    }

    public AssistantMessageItem(String toolCallId, String toolCode, String toolArgs) {
        this.callTool = true;
        this.toolCallId = toolCallId;
        this.toolCode = toolCode;
        this.toolArgs = toolArgs;
    }


    @Override
    public String role() {
        return ROLEEnum.ASSISTANT.getRole();
    }

    @Override
    public String transfer2prompt() {
        if (callTool) {
            return "现在需要调用工具" + toolCode + "参数：" + toolArgs;
        } else {
            return content;
        }

    }
}
