
package com.szh.model.dto.output;

import com.szh.model.dto.output.OutputItem;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * @author demussong
 * @describe Responses API 响应：一轮可包含多个输出项
 * @date 2026/8/31
 */
@Data
@AllArgsConstructor
public class ResponseModelResp {

    private List<OutputItem> items;
}
