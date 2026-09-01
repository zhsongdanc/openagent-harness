
package com.szh.model.dto.output;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * @author demussong
 * @describe 未知类型输出项（如 web_search_call），兜底使用
 * @date 2026/8/31
 */
@Data
@AllArgsConstructor
public class UnknownOutputItem extends OutputItem {

    private String rawType;

    @Override
    public String type() {
        return rawType;
    }
}
