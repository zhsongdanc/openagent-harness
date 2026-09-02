package com.szh.context.dto;

import com.szh.model.dto.ROLEEnum;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author demussong
 * @describe
 * @date 2026/8/25 13:53
 */
@Data
@NoArgsConstructor
public class ToolMessageItem implements MessageItem {

    private String callId;
    private String toolCode;
    private String execResult;

    public ToolMessageItem(String callId, String toolCode, String execRes) {
        this.callId = callId;
        this.toolCode = toolCode;
        this.execResult = execRes;
    }

    @Override
    public String role() {
        return ROLEEnum.TOOL.getRole();
    }

    @Override
    public String transfer2prompt() {
        return "已经执行工具code:" + toolCode + "，结果为：" + execResult;
    }
}
