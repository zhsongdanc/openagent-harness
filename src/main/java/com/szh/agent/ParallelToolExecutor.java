package com.szh.agent;

import com.szh.context.dto.MessageItem;
import com.szh.context.dto.ToolMessageItem;
import com.szh.event.CallToolFinishedEvent;
import com.szh.event.CallToolStartedEvent;
import com.szh.model.dto.ToolCall;
import com.szh.tool.Tool;
import com.szh.tool.ToolContext;
import com.szh.tool.ToolRegistry;
import com.szh.tool.store.ToolResultStore;
import com.szh.utils.ConfigUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * @author demussong
 * @describe 并行工具执行器：一轮内模型请求的多个独立工具调用并发执行，缩短总耗时
 * （对标主流 harness 的 parallel tool calls）。两条运行时（AgentRuntime /
 * AgentResponseRuntime 经 FunctionCallHandler）共用本类，保证行为一致。
 * <p>
 * 事件顺序约束（关键）：DeepSeek Responses API 要求同一轮内所有 function_call 连续排列、
 * function_call_output 统一跟在其后。因此事件按「全部 CallToolStarted → 并发执行 →
 * 全部 CallToolFinished（按调用原序）」落库，执行可以乱序完成，落库顺序必须有序。
 * <p>
 * 线程安全约定：AgentState.applyEvent / LoopGuard.record / 权限确认器都已同步，
 * 工具本身（shell 子进程、文件读写）天然可并发；结果聚合与事件回放在主线程串行完成。
 * @date 2026/9/21
 */
@Slf4j
public class ParallelToolExecutor {

    /**
     * 并发度上限：防止模型一轮请求过多工具把线程与 API 配额打爆
     */
    private static final int MAX_PARALLELISM =
            Math.max(1, ConfigUtil.getInt("agent.parallel.maxThreads", 4));

    /**
     * 单轮并行执行的总超时（秒）：兜底防止某个工具挂死拖住整个 run
     */
    private static final long ROUND_TIMEOUT_SECONDS =
            ConfigUtil.getInt("agent.parallel.timeoutSeconds", 300);

    /**
     * 单个工具调用的执行产物：结果文本 + 是否抛异常，供熔断统计与结果呈现
     */
    private record ToolOutcome(String toolCallId, String toolCode, String toolArgs,
                               String rawResult, boolean threw, Tool tool) {
    }

    private ParallelToolExecutor() {
    }

    /**
     * 并发执行一批工具调用并把结果落进事件流。
     *
     * @param calls 本轮全部工具调用（单个调用也走这里，退化为一次任务提交）
     * @return 是否发生过工具调用（供 Responses 运行时判断是否继续循环）
     */
    public static boolean executeBatch(List<ToolCall> calls, ExecutionEnv env) {
        if (calls == null || calls.isEmpty()) {
            return false;
        }

        // 1. 先按原序落全部 started 事件，满足 function_call 连续分组约束
        for (ToolCall call : calls) {
            env.agentState().applyEvent(new CallToolStartedEvent(env.sessionId(), env.runId(),
                    env.turnId(), env.round(), call.toolCode(), call.toolArgs()));
        }

        // 2. 并发执行（结果按调用原序回收）
        List<ToolOutcome> outcomes = executeConcurrently(calls, env);

        // 3. 主线程串行回放：熔断统计 -> 结果呈现（内联/落盘）-> finished 事件
        for (ToolOutcome outcome : outcomes) {
            if (env.loopGuard() != null) {
                env.loopGuard().record(outcome.toolCode(), outcome.toolArgs(),
                        outcome.rawResult(), outcome.threw());
            }
            // 元工具（inlineResult=true，如 read_tool_result）输出直接内联回传，避免二次落盘套娃；
            // 普通工具走落盘策略：以 callId 作为 resultId，超阈值才落盘并回传引用存根
            String toolMsgContent;
            if (outcome.tool() == null || outcome.tool().inlineResult()) {
                toolMsgContent = outcome.rawResult();
            } else {
                toolMsgContent = env.toolResultStore()
                        .presentResult(outcome.toolCallId(), outcome.toolCode(), outcome.rawResult());
            }
            MessageItem toolMsg = new ToolMessageItem(outcome.toolCallId(), outcome.toolCode(), toolMsgContent);
            env.agentState().applyEvent(new CallToolFinishedEvent(env.sessionId(), env.runId(),
                    env.turnId(), env.round(), toolMsg, outcome.toolCode(), outcome.rawResult()));
        }
        return true;
    }

    /**
     * 线程池并发执行，按调用原序收集结果；异常/超时转错误文本，绝不让单个工具拖垮整轮
     */
    private static List<ToolOutcome> executeConcurrently(List<ToolCall> calls, ExecutionEnv env) {
        // 单调用无需起线程池，直接同步执行，省一次调度开销
        if (calls.size() == 1) {
            ToolCall call = calls.get(0);
            return List.of(runSingle(call, env));
        }

        int parallelism = Math.min(calls.size(), MAX_PARALLELISM);
        ExecutorService executor = Executors.newFixedThreadPool(parallelism, runnable -> {
            Thread thread = new Thread(runnable, "parallel-tool-worker");
            thread.setDaemon(true);
            return thread;
        });
        try {
            List<Future<ToolOutcome>> futures = new ArrayList<>();
            for (ToolCall call : calls) {
                futures.add(executor.submit(() -> runSingle(call, env)));
            }
            List<ToolOutcome> outcomes = new ArrayList<>();
            for (int i = 0; i < futures.size(); i++) {
                ToolCall call = calls.get(i);
                try {
                    outcomes.add(futures.get(i).get(ROUND_TIMEOUT_SECONDS, TimeUnit.SECONDS));
                } catch (TimeoutException e) {
                    futures.get(i).cancel(true);
                    outcomes.add(new ToolOutcome(call.toolCallId(), call.toolCode(), call.toolArgs(),
                            "execute failed: 并行执行超时（" + ROUND_TIMEOUT_SECONDS + "s）", true, null));
                } catch (ExecutionException e) {
                    // runSingle 内部已兜底，走到这里说明是 Error 级异常
                    log.error("parallel tool task crashed: {}", call.toolCode(), e.getCause());
                    outcomes.add(new ToolOutcome(call.toolCallId(), call.toolCode(), call.toolArgs(),
                            "execute failed: " + e.getCause().getMessage(), true, null));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    outcomes.add(new ToolOutcome(call.toolCallId(), call.toolCode(), call.toolArgs(),
                            "execute failed: interrupted", true, null));
                }
            }
            return outcomes;
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 执行单个工具调用：查找工具 -> 执行 -> 异常转错误文本（与旧串行路径口径一致，
     * LoopGuard.isErrorResult 依赖这些前缀识别失败）
     */
    private static ToolOutcome runSingle(ToolCall call, ExecutionEnv env) {
        Tool tool = env.toolRegistry().getToolByCode(call.toolCode());
        String result;
        boolean threw = false;
        try {
            if (tool == null) {
                log.warn("tool not found: {}", call.toolCode());
                result = "tool not found: " + call.toolCode();
            } else {
                // 注入 AgentState 与轮次信息：元工具（todo_write / switch_mode）据此把领域事件落进事件日志
                ToolContext ctx = new ToolContext(env.sessionId(), env.runId(),
                        env.workspace(), call.toolArgs());
                ctx.setAgentState(env.agentState());
                ctx.setTurnId(env.turnId());
                ctx.setRound(env.round());
                result = tool.execute(ctx);
            }
        } catch (Exception e) {
            threw = true;
            log.error("tool execute failed: {}", call.toolCode(), e);
            result = "execute failed: " + e.getMessage();
        }
        return new ToolOutcome(call.toolCallId(), call.toolCode(), call.toolArgs(), result, threw, tool);
    }

    /**
     * 执行环境：由运行时在每轮组装，携带事件落库与结果呈现所需的全部依赖
     */
    public record ExecutionEnv(AgentState agentState, ToolRegistry toolRegistry,
                               String sessionId, String runId, String turnId, int round,
                               String workspace, ToolResultStore toolResultStore,
                               LoopGuard loopGuard) {
    }
}
