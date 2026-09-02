
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

    public ReasoningMessageItem(String content) {
        this.content = content;
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
