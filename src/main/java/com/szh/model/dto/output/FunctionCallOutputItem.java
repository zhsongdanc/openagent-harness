
package com.szh.model.dto.output;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * @author demussong
 * @describe 工具调用输出项
 * @date 2026/8/31
 */
@Data
@AllArgsConstructor
public class FunctionCallOutputItem extends OutputItem {

    public static final String TYPE = "function_call";

    private String callId;
    private String name;
    private String arguments;

    @Override
    public String type() {
        return TYPE;
    }
}
