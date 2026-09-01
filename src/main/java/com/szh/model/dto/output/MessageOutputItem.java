
package com.szh.model.dto.output;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * @author demussong
 * @describe 文本回答输出项
 * @date 2026/8/31
 */
@Data
@AllArgsConstructor
public class MessageOutputItem extends OutputItem {

    public static final String TYPE = "message";

    private String content;

    @Override
    public String type() {
        return TYPE;
    }
}
