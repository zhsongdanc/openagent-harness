package com.szh.utils;

/**
 * @author demussong
 * @describe 重试策略：指数退避 + 抖动，参数由 application.properties 统一配置。
 * <p>
 * 对标主流 harness 对瞬时故障（限流 429、网关 5xx、网络抖动）的容错：不是失败即抛，
 * 而是按 base * multiplier^(n-1) 递增等待后重试，叠加随机抖动避免多请求同时重试造成的惊群。
 * @date 2026/9/21
 */
public class RetryPolicy {

    /**
     * 最大尝试次数（含首次），<=1 表示不重试
     */
    private final int maxAttempts;

    /**
     * 首次退避基准毫秒
     */
    private final long baseDelayMs;

    /**
     * 退避上限毫秒
     */
    private final long maxDelayMs;

    /**
     * 退避倍率
     */
    private final double multiplier;

    /**
     * 抖动比例 [0,1]，实际等待 = delay * (1 + rand*jitter)
     */
    private final double jitter;

    public RetryPolicy(int maxAttempts, long baseDelayMs, long maxDelayMs, double multiplier, double jitter) {
        this.maxAttempts = maxAttempts;
        this.baseDelayMs = baseDelayMs;
        this.maxDelayMs = maxDelayMs;
        this.multiplier = multiplier;
        this.jitter = jitter;
    }

    /**
     * 从配置装配；默认 3 次尝试、1s 基准、30s 上限、2 倍率、0.2 抖动
     */
    public static RetryPolicy fromConfig() {
        return new RetryPolicy(
                ConfigUtil.getInt("model.retry.maxAttempts", 3),
                ConfigUtil.getInt("model.retry.baseDelayMs", 1000),
                ConfigUtil.getInt("model.retry.maxDelayMs", 30000),
                ConfigUtil.getDouble("model.retry.multiplier", 2.0),
                ConfigUtil.getDouble("model.retry.jitter", 0.2));
    }

    /**
     * 不重试策略
     */
    public static RetryPolicy noRetry() {
        return new RetryPolicy(1, 0, 0, 1.0, 0);
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    /**
     * 计算第 attempt 次失败后的等待毫秒（attempt 从 1 开始）
     *
     * @param retryAfterMs 服务端 Retry-After 提示（毫秒），>0 时优先采用（仍受上限约束）
     */
    public long delayMs(int attempt, long retryAfterMs) {
        if (retryAfterMs > 0) {
            return Math.min(retryAfterMs, maxDelayMs);
        }
        double delay = baseDelayMs * Math.pow(multiplier, Math.max(0, attempt - 1));
        delay = Math.min(delay, maxDelayMs);
        // 叠加抖动，避免惊群
        double jitterFactor = 1 + (Math.random() * jitter);
        return (long) (delay * jitterFactor);
    }
}
