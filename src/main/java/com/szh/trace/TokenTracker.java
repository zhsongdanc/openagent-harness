package com.szh.trace;

import com.szh.model.dto.TokenUsage;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 跟踪一次 run 内的 token 累计消耗。
 * <p>
 * 每轮 model.call() 后，用服务端返回的 usage 更新累计值；
 * 压缩后，根据新的上下文重新校准 promptTokens。
 *
 * @author demussong
 * @date 2026/9/17
 */
@Slf4j
public class TokenTracker {

    /**
     * 每轮的 token 用量明细
     */
    private final List<TokenUsage> perStepUsages = new ArrayList<>();

    /**
     * 累计 prompt tokens（服务端返回的精确值）
     */
    private long cumulativePromptTokens = 0;

    /**
     * 累计 completion tokens
     */
    private long cumulativeCompletionTokens = 0;

    /**
     * 累计 total tokens
     */
    private long cumulativeTotalTokens = 0;

    /**
     * 累计 cached tokens
     */
    private long cumulativeCachedTokens = 0;

    /**
     * 当前上下文的 prompt token 数（用于判断是否需要压缩）。
     * 初始为 0，每次 model.call() 后用服务端返回的 promptTokens 校准；
     * 压缩后重新估算。
     */
    private int currentContextTokens = 0;

    /**
     * 记录一轮 model.call() 的 token 用量
     */
    public void recordRound(TokenUsage usage) {
        if (usage == null) {
            return;
        }
        perStepUsages.add(usage);
        cumulativePromptTokens += usage.getPromptTokens();
        cumulativeCompletionTokens += usage.getCompletionTokens();
        cumulativeTotalTokens += usage.getTotalTokens();
        cumulativeCachedTokens += usage.getCachedTokens();
        // 用服务端返回的 promptTokens 校准当前上下文 token 数
        currentContextTokens = usage.getPromptTokens();
    }

    /**
     * 压缩后，用估算值重新校准当前上下文 token 数
     */
    public void recalibrateContextTokens(int estimatedTokens) {
        log.info("TokenTracker: recalibrate context tokens from {} to {}", currentContextTokens, estimatedTokens);
        this.currentContextTokens = estimatedTokens;
    }

    public int getCurrentContextTokens() {
        return currentContextTokens;
    }

    public long getCumulativePromptTokens() {
        return cumulativePromptTokens;
    }

    public long getCumulativeCompletionTokens() {
        return cumulativeCompletionTokens;
    }

    public long getCumulativeTotalTokens() {
        return cumulativeTotalTokens;
    }

    public long getCumulativeCachedTokens() {
        return cumulativeCachedTokens;
    }

    public List<TokenUsage> getPerStepUsages() {
        return perStepUsages;
    }

    /**
     * 打印 token 用量摘要
     */
    public String summary() {
        return String.format("TokenUsage[cumulative: prompt=%d, completion=%d, total=%d, cached=%d, steps=%d, currentCtx=%d]",
                cumulativePromptTokens, cumulativeCompletionTokens, cumulativeTotalTokens,
                cumulativeCachedTokens, perStepUsages.size(), currentContextTokens);
    }
}
