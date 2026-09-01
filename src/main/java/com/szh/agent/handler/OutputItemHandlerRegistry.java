
package com.szh.agent.handler;

import com.szh.model.dto.output.OutputItem;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

/**
 * @author demussong
 * @describe 输出项处理器注册中心：按 type 分发，未知类型忽略并告警
 * @date 2026/8/31
 */
@Slf4j
public class OutputItemHandlerRegistry {

    private final Map<String, OutputItemHandler> handlers = new HashMap<>();

    public void register(OutputItemHandler handler) {
        handlers.put(handler.supportType(), handler);
    }

    public HandleResult dispatch(OutputItem item, HandleContext context) {
        OutputItemHandler handler = handlers.get(item.type());
        if (handler == null) {
            log.warn("unsupported output item type: {}, ignored", item.type());
            return HandleResult.none();
        }
        return handler.handle(item, context);
    }

    public static OutputItemHandlerRegistry defaultRegistry() {
        OutputItemHandlerRegistry registry = new OutputItemHandlerRegistry();
        registry.register(new FunctionCallHandler());
        registry.register(new MessageHandler());
        registry.register(new ReasoningHandler());
        return registry;
    }
}
