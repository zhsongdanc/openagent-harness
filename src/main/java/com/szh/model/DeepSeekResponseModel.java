package com.szh.model;

/**
 * @author demussong
 * @describe DeepSeek Responses API 模型：协议与 OpenAI Responses 同构，
 * input 转换、SSE 流式解析、function_call 分组重排、重试退避等通用能力全部复用
 * {@link OpenAiCompatResponseModel}，这里只固定 DeepSeek 的接入点与默认模型名。
 * @date 2026/8/31 19:36
 */

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class DeepSeekResponseModel extends OpenAiCompatResponseModel {

    private static final String BASE_URL = "https://api.deepseek.com";
    private static final String DEFAULT_MODEL = "deepseek-v4-flash";

    public DeepSeekResponseModel(String apiKey) {
        this(apiKey, DEFAULT_MODEL);
    }

    public DeepSeekResponseModel(String apiKey, String modelName) {
        super(BASE_URL, modelName, apiKey, "DeepSeek");
    }
}
