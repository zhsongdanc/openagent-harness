
package com.szh.model.dto.output;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * @author demussong
 * @describe 思维链输出项
 * @date 2026/8/31
 */
@Data
@AllArgsConstructor
public class ReasoningOutputItem extends OutputItem {

    public static final String TYPE = "reasoning";

    private String content;

    @Override
    public String type() {
        return TYPE;
    }
}
