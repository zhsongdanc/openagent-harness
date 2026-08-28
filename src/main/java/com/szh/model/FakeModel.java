package com.szh.model;

import com.szh.context.dto.AssistantMessageItem;
import com.szh.context.dto.MessageItem;
import com.szh.model.dto.ModelResp;
import com.szh.tool.Tool;

import java.util.List;

/**
 * @author demussong
 * @describe
 * @date 2026/8/25 12:22
 */
public class FakeModel implements Model{
    @Override
    public ModelResp call(List<MessageItem> messages, List<Tool> tools) {
        String prompt = messages.get(messages.size() - 1).transfer2prompt();

        if (prompt.contains("晴朗")) {
            return new ModelResp(new AssistantMessageItem("天气晴朗"), null);
        } else {
            return new ModelResp(new AssistantMessageItem("333","queryWeather","23,43"), null);

        }

    }
}
