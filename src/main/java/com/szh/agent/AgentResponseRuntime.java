
package com.szh.agent;

import com.szh.agent.handler.HandleContext;
import com.szh.agent.handler.HandleResult;
import com.szh.agent.handler.OutputItemHandlerRegistry;
import com.szh.agent.verify.VerifyGate;
import com.szh.agent.verify.VerifyPolicy;
import com.szh.agent.verify.VerifyResult;
import com.szh.context.compaction.ContextCompactionManager;
import com.szh.context.compaction.CompactionResult;
import com.szh.context.compaction.ResponseModelSummarizer;
import com.szh.context.compaction.StepCompactor;
import com.szh.context.dto.AssistantMessageItem;
import com.szh.context.dto.MessageItem;
import com.szh.context.dto.ReasoningMessageItem;
import com.szh.context.dto.ToolMessageItem;
import com.szh.context.dto.UserMessageItem;
import com.szh.event.Event;
import com.szh.event.ContextCompactedEvent;
import com.szh.event.RunCompletedEvent;
import com.szh.event.RunStartedEvent;
import com.szh.event.UserMessageEvent;
import com.szh.event.VerificationEvent;
import com.szh.memory.LongTermMemory;
import com.szh.model.ConsoleStreamListener;
import com.szh.model.ResponseModel;
import com.szh.model.StreamListener;
import com.szh.model.dto.ToolCall;
import com.szh.model.dto.output.OutputItem;
import com.szh.model.dto.output.ResponseModelResp;
import com.szh.tool.Tool;
import com.szh.tool.ToolRegistry;
import com.szh.tool.store.ToolResultStore;
import com.szh.tool.tools.meta.PlanModePolicy;
import com.szh.trace.RunTrace;
import com.szh.trace.StepTrace;
import com.szh.trace.TokenTracker;
import com.szh.utils.CommonUtils;
import com.szh.utils.ConfigUtil;
import org.apache.commons.collections4.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * @author demussong
 * @describe 基于 Responses API 的 Agent 运行时：
 * 每轮模型输出可包含多个输出项（reasoning/message/function_call），
 * 逐个分发给对应处理器执行，一轮内的多个工具调用串行执行完毕后再进入下一轮
 * @date 2026/8/31
 */
public class AgentResponseRuntime {

    private static final Logger log = LoggerFactory.getLogger(AgentResponseRuntime.class);

    public static int MAX_ROUND = 200;

    /**
     * 实例级轮次上限，默认取静态 {@link #MAX_ROUND}。子 agent 通过 {@link #setMaxRound(int)} 收紧，
     * 避免改动全局静态量影响主 agent（并行派生时尤甚）。
     */
    private int maxRound = MAX_ROUND;

    public AgentResponseRuntime setMaxRound(int maxRound) {
        this.maxRound = maxRound;
        return this;
    }

    private final ToolRegistry toolRegistry;

    private final AgentState agentState;

    private final ResponseModel model;

    private final OutputItemHandlerRegistry handlerRegistry;

    private final TokenTracker tokenTracker;

    private final ContextCompactionManager compactionManager;

    public AgentResponseRuntime(AgentState agentState, ToolRegistry toolRegistry, ResponseModel model) {
        this.agentState = agentState;
        this.toolRegistry = toolRegistry;
        this.model = model;
        this.handlerRegistry = OutputItemHandlerRegistry.defaultRegistry();
        this.tokenTracker = new TokenTracker();
        // 通过 ResponseModelSummarizer 适配 Responses API，使 L1 LLM 摘要压缩可用
        this.compactionManager = new ContextCompactionManager(new ResponseModelSummarizer(model), tokenTracker);
    }

    public String run(String sessionId, String userInput) {
        String runId = CommonUtils.generateId();
        RunTrace runTrace = new RunTrace(runId, sessionId, System.currentTimeMillis());
        runTrace.setTokenTracker(tokenTracker);

        agentState.incrementTurnId();
        String turnId = getTurnName(agentState.getTurnId());

        agentState.applyEvent(new RunStartedEvent(sessionId, runId, turnId, 0));
        agentState.applyEvent(new UserMessageEvent(sessionId, runId, turnId, 0, new UserMessageItem(userInput), userInput));

        // run 级不变量集中封装：会话/工具结果存储/熔断器/验证闸门/处理器上下文，避免各步骤重复传参
        RunContext ctx = new RunContext(sessionId, runId, turnId, userInput);

        String res = "";
        int round = 0;
        // 本 run 是否触发过上下文压缩：供 L4 反思的 on_compaction 策略判定（信息即将被丢弃才沉淀）
        boolean compactionHappened = false;
        while (true) {
            if (round > maxRound) {
                break;
            }
            round++;
            ctx.handleContext.setRound(round);
            long roundStart = System.currentTimeMillis();

            // 上下文压缩检查：在调用 model 前判断是否需要压缩
            if (maybeCompactContext(ctx, round)) {
                compactionHappened = true;
            }

            ResponseModelResp modelResp = callModel(buildCallContext(ctx), ctx, round);
            // 记录本轮 token 用量（对齐 Chat 链路 AgentRuntime）：既累计到 run 级 tracker，也落到 StepTrace 供回放
            tokenTracker.recordRound(modelResp.getTokenUsage());
            DispatchOutcome outcome = dispatchItems(modelResp, ctx.handleContext);
            // 一轮内多个 function_call 并发执行（取代旧串行）：事件按「全部 started → 并发执行 →
            // 全部 finished」有序落库，满足 Responses API 分组约束，执行细节由 ParallelToolExecutor 兜底
            executeRoundCalls(outcome.roundCalls(), ctx, round);

            StepTrace stepTrace = new StepTrace(round, roundStart, System.currentTimeMillis());
            stepTrace.setTokenUsage(modelResp.getTokenUsage());
            runTrace.addStepTrace(stepTrace);

            // 熔断触发：结束本次 run，把原因作为最终结果回传
            if (ctx.loopGuard.isTripped()) {
                res = ctx.loopGuard.getViolation().getReason();
                break;
            }

            if (!outcome.anyToolCall()) {
                // 模型宣布完成（无工具调用）：交由验证闸门决定是否放行、回灌重修或超限收尾
                VerifyOutcome verdict = handleVerification(ctx, round, outcome.lastMessage());
                if (verdict.shouldContinue()) {
                    continue;
                }
                if (verdict.shouldBreak()) {
                    res = verdict.result();
                    break;
                }
                if (outcome.lastMessage() != null) {
                    res = outcome.lastMessage();
                }
                break;
            }
        }

        if (round > maxRound) {
            res = "reach max round";
        } else if (Objects.equals(res, "")) {
            res = "unknown error";
        }
        agentState.applyEvent(new RunCompletedEvent(sessionId, runId, turnId, round, res));
        // L4 长期记忆：run 结束后按策略决定是否异步反思抽取（复用 Responses API 摘要器，不再每轮同步蒸馏）
        LongTermMemory.get().maybeReflectOnRunEnd(sessionId, agentState.getModelContext(),
                new ResponseModelSummarizer(model), compactionHappened);
        runTrace.setEndTime(System.currentTimeMillis());
        runTrace.printTraceByRunId(runId);
        return res;
    }

    /**
     * 上下文压缩检查：超阈值先 L0 规则压缩、仍超再升级 L1 LLM 摘要，并把 L1 摘要落检查点事件，
     * 使 resume 能直接折叠重建到压缩态、无需再调 LLM 重压。
     *
     * @return 本轮是否实际发生了压缩（供 run 累计 compactionHappened 标记）
     */
    private boolean maybeCompactContext(RunContext ctx, int round) {
        logContextState(round, agentState.getModelContext());
        int beforeCompaction = agentState.getModelContext().size();
        CompactionResult cr = compactionManager.maybeCompact(new ArrayList<>(agentState.getModelContext()));
        if (!cr.isChanged()) {
            return false;
        }
        log.info("[Round {}] >>> 压缩已执行: 消息数 {} -> {} <<<", round, beforeCompaction, cr.getMessages().size());
        agentState.replaceModelContext(cr.getMessages());
        // L1 摘要落检查点事件：使 resume 能直接折叠重建到压缩态，无需再调 LLM 重压
        if (cr.isSummarized()) {
            agentState.applyEvent(new ContextCompactedEvent(ctx.sessionId, ctx.runId, ctx.turnId, round,
                    cr.getSummary(), cr.getKeepRecentCount(), beforeCompaction, cr.getMessages().size()));
        }
        return true;
    }

    /**
     * 构造本轮发给模型的上下文副本：叠加 L4 记忆召回、PLAN 模式契约、验证软提示与护栏纠偏指令，
     * 全部只改副本不动事件真相源。
     */
    private List<MessageItem> buildCallContext(RunContext ctx) {
        List<MessageItem> callContext = new ArrayList<>(agentState.getModelContext());
        // L4 长期记忆：按本轮用户输入召回相关记忆，合并进 system prompt
        LongTermMemory.get().injectRecall(callContext, ctx.userInput);
        // PLAN 模式：往 system prompt 追加规划契约，并把工具收敛为只读子集（会话级模式，每轮读取）
        PlanModePolicy.decorateContext(callContext, ctx.sessionId);
        // 验证闭环软提示：闸门武装时往 system prompt 追加「收尾前自查」契约
        VerifyPolicy.decorateContext(callContext, ctx.verifyGate.isArmed());
        // 循环护栏软提示：连续故障达软阈值时注入纠偏指令，给模型改策略的机会（硬熔断前的缓冲档）
        ctx.loopGuard.decorateContext(callContext);
        return callContext;
    }

    /**
     * 调用模型：按 PLAN 模式收敛有效工具集，按开关装配流式监听器（增量 token 实时打到控制台）。
     */
    private ResponseModelResp callModel(List<MessageItem> callContext, RunContext ctx, int round) {
        List<Tool> effectiveTools = PlanModePolicy.effectiveTools(toolRegistry.getTools(), ctx.sessionId);
        StreamListener streamListener = ConfigUtil.getBoolean("model.stream.enabled", true)
                ? new ConsoleStreamListener(ConfigUtil.getBoolean("model.stream.printReasoning", false))
                : null;
        ResponseModelResp modelResp = model.call(callContext, effectiveTools, streamListener);
        log.debug("call model, round:{}", round);
        return modelResp;
    }

    /**
     * 逐个分发本轮模型输出项给对应处理器，收齐是否发生工具调用、最终消息与待并行执行的调用列表。
     */
    private DispatchOutcome dispatchItems(ResponseModelResp modelResp, HandleContext handleContext) {
        boolean anyToolCall = false;
        String lastMessage = null;
        List<ToolCall> roundCalls = new ArrayList<>();
        for (OutputItem item : modelResp.getItems()) {
            HandleResult result = handlerRegistry.dispatch(item, handleContext);
            if (result.isToolCallExecuted()) {
                anyToolCall = true;
            }
            if (result.getCollectedCalls() != null) {
                roundCalls.addAll(result.getCollectedCalls());
            }
            if (result.getMessageContent() != null) {
                lastMessage = result.getMessageContent();
            }
        }
        return new DispatchOutcome(anyToolCall, lastMessage, roundCalls);
    }

    /**
     * 并发执行本轮收集到的工具调用：命中写入类工具即置 dirty（供验证闸门 onlyWhenDirty 判定），
     * 空列表直接跳过。
     */
    private void executeRoundCalls(List<ToolCall> roundCalls, RunContext ctx, int round) {
        if (roundCalls.isEmpty()) {
            return;
        }
        roundCalls.forEach(c -> ctx.verifyGate.markDirty(c.toolCode()));
        ParallelToolExecutor.executeBatch(roundCalls, new ParallelToolExecutor.ExecutionEnv(
                agentState, toolRegistry, ctx.sessionId, ctx.runId, ctx.turnId, round,
                ctx.workspace, ctx.toolResultStore, ctx.loopGuard));
    }

    /**
     * 收尾验证闸门：模型宣布完成时，若闸门武装则强制跑验证命令。
     * 助手最终消息已由 MessageHandler 落库，此处追加 user 消息不破坏 function_call 分组约束。
     *
     * @return 三态决策——回灌重修后继续下一轮 / 带说明收尾 / 放行收尾（由 run 取 lastMessage 收尾）
     */
    private VerifyOutcome handleVerification(RunContext ctx, int round, String lastMessage) {
        VerifyGate verifyGate = ctx.verifyGate;
        if (!verifyGate.shouldVerify()) {
            return VerifyOutcome.FINISH;
        }
        VerifyResult vr = verifyGate.runVerification();
        agentState.applyEvent(new VerificationEvent(ctx.sessionId, ctx.runId, ctx.turnId, round,
                vr.passed(), vr.failedCommand(), vr.exitCode(), vr.note()));
        if (!vr.passed() && verifyGate.canRetry()) {
            verifyGate.incrementRound();
            String feedback = vr.feedback(verifyGate.round(), verifyGate.maxFixRounds());
            log.warn("[Round {}] 验证未通过，回灌失败输出逼模型重修（第 {}/{} 次）",
                    round, verifyGate.round(), verifyGate.maxFixRounds());
            agentState.applyEvent(new UserMessageEvent(ctx.sessionId, ctx.runId, ctx.turnId, round,
                    new UserMessageItem(feedback), feedback));
            return VerifyOutcome.CONTINUE;
        }
        if (!vr.passed()) {
            // 修复轮次耗尽：带「验证未通过」说明收尾，让用户明确知道未通过，而非静默成功
            String result = "验证未通过（已尝试 " + verifyGate.round() + " 次修复）：\n" + vr.summary()
                    + "\n\n模型最后的答复：\n" + (lastMessage == null ? "" : lastMessage);
            return VerifyOutcome.breakWith(result);
        }
        log.info("[Round {}] 验证通过，放行收尾", round);
        return VerifyOutcome.FINISH;
    }

    /**
     * run 级不变量容器：一次 run 内复用的会话标识、工作区、工具结果存储、熔断器、验证闸门与处理器上下文。
     * 集中封装以消除各步骤方法间的重复传参。
     */
    private final class RunContext {
        final String sessionId;
        final String runId;
        final String turnId;
        final String userInput;
        final String workspace;
        final ToolResultStore toolResultStore;
        final LoopGuard loopGuard;
        final VerifyGate verifyGate;
        final HandleContext handleContext;

        RunContext(String sessionId, String runId, String turnId, String userInput) {
            this.sessionId = sessionId;
            this.runId = runId;
            this.turnId = turnId;
            this.userInput = userInput;
            this.workspace = ConfigUtil.get("project.workspace", System.getProperty("user.dir"));
            // session 级持久化工具结果存储：整个 run 复用同一实例，保证 resultId 单调递增
            this.toolResultStore = new ToolResultStore(workspace, sessionId);
            // run 级熔断器：检测连续失败 / 重复调用，及时刹车
            this.loopGuard = new LoopGuard();
            // run 级验证闸门：改过代码后模型宣布完成时强制跑编译/测试，不通过则回灌失败逼其重修
            this.verifyGate = new VerifyGate(workspace);
            this.handleContext = new HandleContext(agentState, toolRegistry, sessionId, runId, turnId, 0,
                    workspace, toolResultStore, loopGuard);
        }
    }

    /**
     * 一轮输出项分发的聚合结果：是否发生工具调用、模型最终消息、待并行执行的工具调用列表。
     */
    private record DispatchOutcome(boolean anyToolCall, String lastMessage, List<ToolCall> roundCalls) {
    }

    /**
     * 验证闸门三态决策：CONTINUE=回灌后继续下一轮、shouldBreak=带说明收尾、FINISH=放行由 run 收尾。
     */
    private static final class VerifyOutcome {
        static final VerifyOutcome FINISH = new VerifyOutcome(false, false, null);
        static final VerifyOutcome CONTINUE = new VerifyOutcome(true, false, null);

        private final boolean shouldContinue;
        private final boolean shouldBreak;
        private final String result;

        private VerifyOutcome(boolean shouldContinue, boolean shouldBreak, String result) {
            this.shouldContinue = shouldContinue;
            this.shouldBreak = shouldBreak;
            this.result = result;
        }

        static VerifyOutcome breakWith(String result) {
            return new VerifyOutcome(false, true, result);
        }

        boolean shouldContinue() {
            return shouldContinue;
        }

        boolean shouldBreak() {
            return shouldBreak;
        }

        String result() {
            return result;
        }
    }

    /**
     * 会话结束时触发一次 L4 反思（仅 session_end 策略生效），供 REPL 在退出/切换会话前调用。
     * 复用本运行时的 model 构造摘要器，与 run 内反思走同一套 Responses API 链路。
     */
    public void reflectOnSessionEnd(String sessionId) {
        LongTermMemory.get().maybeReflectOnSessionEnd(sessionId, agentState.getModelContext(),
                new ResponseModelSummarizer(model));
    }

    private String getTurnName(int turnId) {
        return "turn_" + turnId;
    }

    /**
     * 打印当前轮上下文状态（消息数、估算 tokens、各类型 item 分布），
     * 便于排查 reasoning 是否正确回传。仅用于诊断日志，与主流程解耦。
     */
    private void logContextState(int round, List<MessageItem> ctx) {
        if (!log.isInfoEnabled()) {
            return;
        }
        int ctxTokens = StepCompactor.estimateTotalTokens(ctx);
        long reasoningCount = ctx.stream().filter(m -> m instanceof ReasoningMessageItem).count();
        long toolCallCount = ctx.stream().filter(m -> m instanceof AssistantMessageItem a && a.isCallTool()).count();
        long toolResultCount = ctx.stream().filter(m -> m instanceof ToolMessageItem).count();
        log.info("[Round {}] 上下文状态: 消息数={}, 估算tokens={}, 类型分布=[reasoning={}, toolCall={}, toolResult={}]",
                round, ctx.size(), ctxTokens, reasoningCount, toolCallCount, toolResultCount);
    }

    public void printLog(String sessionId) {
        List<Event> events = agentState.getEventStore().getEvents(sessionId);
        if (CollectionUtils.isEmpty(events)) {
            log.info("no events recorded");
            return;
        }

        List<Event> sorted = new ArrayList<>(events);
        sorted.sort(Comparator.comparing(Event::getTimestamp));

        log.info("===== Event Log (total: {}) =====", sorted.size());
        for (int i = 0; i < sorted.size(); i++) {
            Event event = sorted.get(i);
            log.info("[{}] {} | time: {} | sessionId: {} | turnId: {} | round: {}",
                    i + 1,
                    event.getType(),
                    event.getTimestamp(),
                    event.getSessionId(),
                    event.getTurnId(),
                    event.getRound());
        }
        log.info("===== End Event Log =====");
    }
}
