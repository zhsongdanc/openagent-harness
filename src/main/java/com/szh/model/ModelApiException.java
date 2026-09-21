package com.szh.model;

import com.szh.utils.RetryExecutor;

/**
 * @author demussong
 * @describe 模型 API 调用异常：携带 HTTP 状态码与服务端 Retry-After 提示，供重试器判定是否可重试。
 * <p>
 * 可重试判定对标主流做法：429（限流）、408（超时）、5xx（服务端错误）视为瞬时故障可重试；
 * 其余 4xx（如 400 参数错误、401 鉴权失败）是确定性错误，重试无意义，立即失败。
 * @date 2026/9/21
 */
public class ModelApiException extends RuntimeException implements RetryExecutor.RetryAfterAware {

    private final int statusCode;

    private final long retryAfterMs;

    public ModelApiException(int statusCode, String message) {
        this(statusCode, message, 0);
    }

    public ModelApiException(int statusCode, String message, long retryAfterMs) {
        super(message);
        this.statusCode = statusCode;
        this.retryAfterMs = retryAfterMs;
    }

    public int getStatusCode() {
        return statusCode;
    }

    @Override
    public long getRetryAfterMs() {
        return retryAfterMs;
    }

    /**
     * 是否为可重试的瞬时错误
     */
    public boolean isRetryable() {
        return statusCode == 429 || statusCode == 408 || statusCode >= 500;
    }
}
