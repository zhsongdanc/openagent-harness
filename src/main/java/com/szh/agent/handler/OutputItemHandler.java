
package com.szh.agent.handler;

import com.szh.model.dto.output.OutputItem;

/**
 * @author demussong
 * @describe 输出项适配器：每种 Responses API 输出项类型对应一个处理器
 * @date 2026/8/31
 */
public interface OutputItemHandler {

    String supportType();

    HandleResult handle(OutputItem item, HandleContext context);
}
