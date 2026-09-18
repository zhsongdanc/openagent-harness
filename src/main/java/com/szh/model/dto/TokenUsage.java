package com.szh.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单次 model.call() 服务端返回的 token 用量
 *
 * @author demussong
 * @date 2026/9/17
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class TokenUsage {

    private int promptTokens;

    private int completionTokens;

    private int totalTokens;

    /**
     * 服务端缓存命中的 token 数（可选，部分 provider 返回）
     */
    private int cachedTokens;

    public TokenUsage(int promptTokens, int completionTokens, int totalTokens) {
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = totalTokens;
        this.cachedTokens = 0;
    }
}
