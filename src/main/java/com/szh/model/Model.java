package com.szh.model;

import com.szh.context.dto.AssistantMessageItem;
import com.szh.context.dto.MessageItem;

/**
 * @author demussong
 * @describe
 * @date 2026/8/25 12:09
 */
public interface Model {

    AssistantMessageItem call(String prompt);
}
