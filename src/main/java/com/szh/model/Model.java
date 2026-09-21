package com.szh.model;

import com.szh.context.dto.MessageItem;
import com.szh.model.dto.ModelResp;
import com.szh.tool.Tool;

import java.util.List;

/**
 * @author demussong
 * @describe
 * @date 2026/8/25 12:09
 */
public interface Model {

    public ModelResp call(List<MessageItem> messages, List<Tool> tools);

    /**
     * 流式调用：增量 token 通过 listener 回调，返回值仍为聚合后的完整响应。
     * 默认实现忽略 listener 直接走阻塞调用，Provider 按能力覆写（如 OpenAI 兼容 SSE）。
     */
    default ModelResp call(List<MessageItem> messages, List<Tool> tools, StreamListener listener) {
        return call(messages, tools);
    }
}
