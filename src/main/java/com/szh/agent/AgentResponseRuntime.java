
package com.szh.agent;

import com.szh.agent.handler.HandleContext;
import com.szh.agent.handler.HandleResult;
import com.szh.agent.handler.OutputItemHandlerRegistry;
import com.szh.context.compaction.ContextCompactionManager;
import com.szh.context.compaction.ResponseModelSummarizer;
import com.szh.context.compaction.StepCompactor;
import com.szh.context.dto.AssistantMessageItem;
import com.szh.context.dto.MessageItem;
import com.szh.context.dto.ReasoningMessageItem;
import com.szh.context.dto.ToolMessageItem;
import com.szh.context.dto.UserMessageItem;
import com.szh.event.Event;
import com.szh.event.RunCompletedEvent;
import com.szh.event.RunStartedEvent;
import com.szh.event.UserMessageEvent;
import com.szh.memory.LongTermMemory;
import com.szh.model.ConsoleStreamListener;
import com.szh.model.ResponseModel;
import com.szh.model.StreamListener;
import com.szh.model.dto.ToolCall;
import com.szh.model.dto.output.OutputItem;
import com.szh.model.dto.output.ResponseModelResp;
import com.szh.tool.ToolRegistry;
import com.szh.tool.store.ToolResultStore;
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

        agentState.incrementTurnId();
        String turnId = getTurnName(agentState.getTurnId());

        agentState.applyEvent(new RunStartedEvent(sessionId, runId, turnId, 0));
        agentState.applyEvent(new UserMessageEvent(sessionId, runId, turnId, 0, new UserMessageItem(userInput), userInput));

        // session 级持久化工具结果存储：整个 run 复用同一实例，保证 resultId 单调递增
        String workspace = ConfigUtil.get("project.workspace", System.getProperty("user.dir"));
        ToolResultStore toolResultStore = new ToolResultStore(workspace, sessionId);
        // run 级熔断器：检测连续失败 / 重复调用，及时刹车
        LoopGuard loopGuard = new LoopGuard();

        HandleContext handleContext = new HandleContext(agentState, toolRegistry, sessionId, runId, turnId, 0,
                workspace, toolResultStore, loopGuard);

        String res = "";
        int round = 0;
        while (true) {
            if (round > maxRound) {
                break;
            }
            round++;
            handleContext.setRound(round);
            long roundStart = System.currentTimeMillis();

            // 上下文压缩检查：在调用 model 前判断是否需要压缩
            logContextState(round, agentState.getModelContext());
            List<MessageItem> compacted = compactionManager.maybeCompact(new ArrayList<>(agentState.getModelContext()));
            if (compacted.size() != agentState.getModelContext().size()) {
                log.info("[Round {}] >>> 压缩已执行: 消息数 {} -> {} <<<", round, agentState.getModelContext().size(), compacted.size());
                agentState.replaceModelContext(compacted);
            }

            List<MessageItem> callContext = new ArrayList<>(agentState.getModelContext());
            // L4 长期记忆：按本轮用户输入召回相关记忆，合并进 system prompt（只改副本，不动事件真相源）
            LongTermMemory.get().injectRecall(callContext, userInput);
            // 流式输出：增量 token 实时打到控制台（model.stream.enabled 可关）
            StreamListener streamListener = ConfigUtil.getBoolean("model.stream.enabled", true)
                    ? new ConsoleStreamListener(ConfigUtil.getBoolean("model.stream.printReasoning", false))
                    : null;
            ResponseModelResp modelResp = model.call(callContext, toolRegistry.getTools(), streamListener);
            log.debug("call model, round:{}", round);

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

            // 一轮内多个 function_call 并发执行（取代旧串行）：事件按「全部 started → 并发执行 →
            // 全部 finished」有序落库，满足 Responses API 分组约束，执行细节由 ParallelToolExecutor 兜底
            if (!roundCalls.isEmpty()) {
                ParallelToolExecutor.executeBatch(roundCalls, new ParallelToolExecutor.ExecutionEnv(
                        agentState, toolRegistry, sessionId, runId, turnId, round,
                        workspace, toolResultStore, loopGuard));
            }

            StepTrace stepTrace = new StepTrace(round, roundStart, System.currentTimeMillis());
            runTrace.addStepTrace(stepTrace);

            // 熔断触发：结束本次 run，把原因作为最终结果回传
            if (loopGuard.isTripped()) {
                res = loopGuard.getViolation().getReason();
                break;
            }

            if (!anyToolCall) {
                if (lastMessage != null) {
                    res = lastMessage;
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
        // L4 长期记忆：run 结束后反思抽取（复用 Responses API 摘要器）
        LongTermMemory.get().reflectAndStore(sessionId, agentState.getModelContext(), new ResponseModelSummarizer(model));
        runTrace.setEndTime(System.currentTimeMillis());
        runTrace.printTraceByRunId(runId);
        return res;
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
