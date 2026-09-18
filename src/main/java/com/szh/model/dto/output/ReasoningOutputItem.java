
package com.szh.model.dto.output;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * @author demussong
 * @describe 思维链输出项
 * @date 2026/8/31
 */
@Data
public class ReasoningOutputItem extends OutputItem {

    public static final String TYPE = "reasoning";

    private String content;

    /** 原始 content JSON 字符串，回传时原样透传给 API（含 encrypted_content 等） */
    private String rawContentJson;

    public ReasoningOutputItem(String content, String rawContentJson) {
        this.content = content;
        this.rawContentJson = rawContentJson;
    }

    @Override
    public String type() {
        return TYPE;
    }
}
