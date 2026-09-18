
package com.szh.context.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author demussong
 * @describe 思维链历史项，回传 Responses API 时转为 reasoning item
 * @date 2026/8/31
 */
@Data
@NoArgsConstructor
public class ReasoningMessageItem implements MessageItem {

    private String content;

    /** 原始 content JSON 字符串，回传时原样透传给 API */
    private String rawContentJson;

    public ReasoningMessageItem(String content) {
        this.content = content;
    }

    public ReasoningMessageItem(String content, String rawContentJson) {
        this.content = content;
        this.rawContentJson = rawContentJson;
    }

    @Override
    public String role() {
        return "reasoning";
    }

    @Override
    public String transfer2prompt() {
        return content;
    }
}
