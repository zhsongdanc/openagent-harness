package com.szh.model;

import com.szh.context.dto.AssistantMessageItem;
import com.szh.context.dto.MessageItem;
import com.szh.model.dto.ModelResp;
import com.szh.tool.ToolDefinition;

import java.util.List;

/**
 * @author demussong
 * @describe
 * @date 2026/8/25 12:09
 */
public interface Model {

    public ModelResp call(List<MessageItem> messages, List<ToolDefinition> tools);
}
