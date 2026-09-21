package com.szh.utils;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Callable;
import java.util.function.Predicate;

/**
 * @author demussong
 * @describe 通用重试执行器：按 {@link RetryPolicy} 对可重试异常做指数退避重试。
 * <p>
 * 与具体业务解耦——调用方传入任务、策略和「哪些异常值得重试」的判定谓词即可。
 * 只对可重试异常（如限流、网关错误、网络抖动）重试；不可重试异常（如参数错误 4xx）立即抛出，
 * 避免无意义地放大失败延迟。等待期间被中断会恢复中断标志并终止重试。
 * @date 2026/9/21
 */
@Slf4j
public class RetryExecutor {

    private RetryExecutor() {
    }

    /**
     * 带重试地执行任务
     *
     * @param task      待执行任务
     * @param policy    重试策略
     * @param retryable 判定异常是否可重试
     * @param taskName  日志用的任务名
     * @return 任务结果
     * @throws Exception 重试耗尽或遇到不可重试异常时抛出最后一次异常
     */
    public static <T> T execute(Callable<T> task, RetryPolicy policy,
                                Predicate<Exception> retryable, String taskName) throws Exception {
        int maxAttempts = Math.max(1, policy.getMaxAttempts());
        Exception last = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return task.call();
            } catch (Exception e) {
                last = e;
                boolean canRetry = attempt < maxAttempts && retryable.test(e);
                if (!canRetry) {
                    throw e;
                }
                long retryAfterMs = extractRetryAfter(e);
                long delay = policy.delayMs(attempt, retryAfterMs);
                log.warn("{} failed (attempt {}/{}), retrying in {}ms: {}",
                        taskName, attempt, maxAttempts, delay, e.getMessage());
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        // 理论上不会到这里（循环内要么返回要么抛出）
        throw last != null ? last : new IllegalStateException("retry exhausted: " + taskName);
    }

    /**
     * 从异常中提取服务端 Retry-After 提示（毫秒），无法识别返回 0
     */
    private static long extractRetryAfter(Exception e) {
        if (e instanceof RetryAfterAware aware) {
            return aware.getRetryAfterMs();
        }
        return 0;
    }

    /**
     * 携带服务端 Retry-After 提示的异常可实现此接口，重试时优先采用该等待时长
     */
    public interface RetryAfterAware {
        long getRetryAfterMs();
    }
}
