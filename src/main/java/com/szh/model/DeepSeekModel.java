package com.szh.model;

import lombok.extern.slf4j.Slf4j;

/**
 * @author demussong
 * @describe DeepSeek Chat Completions 模型：DeepSeek 的 API 是 OpenAI 兼容协议，
 * 请求构造、SSE 流式解析、并行 tool_calls、重试退避等通用能力全部复用
 * {@link OpenAiCompatModel}，这里只固定 DeepSeek 的接入点与默认模型名。
 * @date 2026/8/25 12:09
 */
@Slf4j
public class DeepSeekModel extends OpenAiCompatModel {

    private static final String BASE_URL = "https://api.deepseek.com";
    private static final String DEFAULT_MODEL = "deepseek-chat";

    public DeepSeekModel(String apiKey) {
        this(apiKey, DEFAULT_MODEL);
    }

    public DeepSeekModel(String apiKey, String modelName) {
        super(BASE_URL, modelName, apiKey, "DeepSeek");
    }
}
