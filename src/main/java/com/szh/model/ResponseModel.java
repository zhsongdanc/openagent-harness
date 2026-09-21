
package com.szh.model;

import com.szh.context.dto.MessageItem;
import com.szh.model.dto.output.ResponseModelResp;
import com.szh.tool.Tool;

import java.util.List;

/**
 * @author demussong
 * @describe Responses API 模型接口，返回多个输出项
 * @date 2026/8/31
 */
public interface ResponseModel {

    ResponseModelResp call(List<MessageItem> messages, List<Tool> tools);

    /**
     * 流式调用：增量 token 通过 listener 回调，返回值仍为聚合后的完整响应。
     * 默认实现忽略 listener 直接走阻塞调用，Provider 按能力覆写。
     */
    default ResponseModelResp call(List<MessageItem> messages, List<Tool> tools, StreamListener listener) {
        return call(messages, tools);
    }
}
