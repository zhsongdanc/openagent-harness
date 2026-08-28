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
}
