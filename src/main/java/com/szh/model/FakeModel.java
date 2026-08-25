package com.szh.model;

import com.szh.context.dto.AssistantMessageItem;
import com.szh.context.dto.MessageItem;

/**
 * @author demussong
 * @describe
 * @date 2026/8/25 12:22
 */
public class FakeModel implements Model{
    @Override
    public AssistantMessageItem call(String prompt) {

        if (prompt.contains("晴朗")) {
            return new AssistantMessageItem(true, false,"queryWeather","天气晴朗");
        } else {
            return new AssistantMessageItem(false, true,"queryWeather","调用查询工具接口");

        }

    }
}
