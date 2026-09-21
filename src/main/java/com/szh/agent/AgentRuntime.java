package com.szh.agent;

import com.szh.context.ContextBuilder;
import com.szh.context.compaction.ContextCompactionManager;
import com.szh.context.compaction.ModelSummarizer;
import com.szh.context.compaction.StepCompactor;
import com.szh.context.dto.*;
import com.szh.event.*;
import com.szh.memory.LongTermMemory;
import com.szh.model.ConsoleStreamListener;
import com.szh.model.Model;
import com.szh.model.StreamListener;
import com.szh.model.dto.ActionEnum;
import com.szh.model.dto.ModelResp;
import com.szh.model.dto.ToolCall;
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
 * @describe
 * @date 2026/8/25 12:09
 */
public class AgentRuntime {

    private static final Logger log = LoggerFactory.getLogger(AgentRuntime.class);

    private ToolRegistry toolRegistry;

    private AgentState agentState;

    private Model model;

    private TokenTracker tokenTracker;

    private ContextCompactionManager compactionManager;

    private ToolResultStore toolResultStore;

    public AgentRuntime(AgentState agentState, ToolRegistry toolRegistry, Model model) {
        this.agentState = agentState;
        this.toolRegistry = toolRegistry;
        this.model = model;
        this.tokenTracker = new TokenTracker();
        this.compactionManager = new ContextCompactionManager(new ModelSummarizer(model), tokenTracker);
    }

    /**
     * 初始化本次 run 的工具结果存储。在 run() 开始时调用，需要 sessionId 确定存储目录。
     */
    private void initToolResultStore(String sessionId) {
        String workspace = ConfigUtil.get("project.workspace", System.getProperty("user.dir"));
        this.toolResultStore = new ToolResultStore(workspace, sessionId);
    }

    public static int MAX_ROUND = 100;

    /**
     * 实例级轮次上限，默认取静态 {@link #MAX_ROUND}。子 agent 通过 {@link #setMaxRound(int)} 收紧，
     * 避免改动全局静态量影响主 agent（并行派生时尤甚）。
     */
    private int maxRound = MAX_ROUND;

    public AgentRuntime setMaxRound(int maxRound) {
        this.maxRound = maxRound;
        return this;
    }

    public String run(String sessionId, String userInput) {
        String runId = CommonUtils.generateId();
        RunTrace runTrace = new RunTrace(runId, sessionId, System.currentTimeMillis());
        runTrace.setTokenTracker(tokenTracker);
        initToolResultStore(sessionId);
        // run 级熔断器：检测连续失败 / 重复调用，及时刹车
        LoopGuard loopGuard = new LoopGuard();

        agentState.incrementTurnId();
        String turnId = getTurnName(agentState.getTurnId());

        agentState.applyEvent(new RunStartedEvent(sessionId, runId, turnId, 0));
        MessageItem messageItem = new UserMessageItem(userInput);
        UserMessageEvent userMessageEvent = new UserMessageEvent(sessionId, runId, turnId, 0, messageItem, userInput);
        agentState.applyEvent(userMessageEvent);

        String res = "";
        int round = 0;
        while (true) {
            long roundStart = System.currentTimeMillis();

            if (round > maxRound) {
                break;
            }
            round++;

            // 上下文压缩检查：在调用 model 前判断是否需要压缩
            int ctxTokens = StepCompactor.estimateTotalTokens(agentState.getModelContext());
            log.info("[Round {}] 上下文状态: 消息数={}, 估算tokens={}", round, agentState.getModelContext().size(), ctxTokens);
            List<MessageItem> compacted = compactionManager.maybeCompact(new ArrayList<>(agentState.getModelContext()));
            if (compacted.size() != agentState.getModelContext().size()) {
                log.info("[Round {}] >>> 压缩已执行: 消息数 {} -> {} <<<", round, agentState.getModelContext().size(), compacted.size());
                agentState.replaceModelContext(compacted);
            }

            String context = ContextBuilder.buildContext(agentState);
            List<MessageItem> callContext = new ArrayList<>(agentState.getModelContext());
            // L4 长期记忆：按本轮用户输入召回相关记忆，合并进 system prompt（只改副本，不动事件真相源）
            LongTermMemory.get().injectRecall(callContext, userInput);
            // 流式输出：增量 token 实时打到控制台，长回答不再阻塞等待（model.stream.enabled 可关）
            StreamListener streamListener = ConfigUtil.getBoolean("model.stream.enabled", true)
                    ? new ConsoleStreamListener(ConfigUtil.getBoolean("model.stream.printReasoning", false))
                    : null;
            ModelResp modelResp = model.call(callContext, toolRegistry.getTools(), streamListener);

            // 记录 token 用量
            tokenTracker.recordRound(modelResp.getTokenUsage());

            ModelResponseEvent modelResponseEvent = new ModelResponseEvent(sessionId, runId, turnId, round, modelResp.getMessage());
            agentState.applyEvent(modelResponseEvent);

            if (modelResp.getAction() == ActionEnum.FINAL_ANSWER) {
                res =  modelResp.getMessage().getContent();

                StepTrace stepTrace = new StepTrace(round, roundStart, System.currentTimeMillis());
                stepTrace.setTokenUsage(modelResp.getTokenUsage());
                runTrace.addStepTrace(stepTrace);
                break;
            }

            if (modelResp.getAction() == ActionEnum.TOOL_CALL) {
                AssistantMessageItem toolMessage = modelResp.getMessage();
                // 一轮可能包含多个并行工具调用：事件按「全部 started → 并发执行 → 全部 finished」
                // 有序落库，执行本体交给 ParallelToolExecutor（含工具查找/异常兜底/熔断统计/落盘呈现）
                List<ToolCall> calls = toolMessage.effectiveToolCalls();
                ParallelToolExecutor.executeBatch(calls, new ParallelToolExecutor.ExecutionEnv(
                        agentState, toolRegistry, sessionId, runId, turnId, round,
                        ConfigUtil.get("project.workspace", System.getProperty("user.dir")),
                        toolResultStore, loopGuard));

                // 熔断触发：结束本次 run，把原因作为最终结果回传
                if (loopGuard.isTripped()) {
                    res = loopGuard.getViolation().getReason();
                    StepTrace guardTrace = new StepTrace(round, roundStart, System.currentTimeMillis());
                    guardTrace.setTokenUsage(modelResp.getTokenUsage());
                    runTrace.addStepTrace(guardTrace);
                    break;
                }
            }
            StepTrace stepTrace = new StepTrace(round, roundStart, System.currentTimeMillis());
            stepTrace.setTokenUsage(modelResp.getTokenUsage());
            runTrace.addStepTrace(stepTrace);
        }

        if (round > maxRound) {
            res = "reach max round";
        } else if (Objects.equals(res, "")) {
            res = "unknown error";
        }
        agentState.applyEvent(new RunCompletedEvent(sessionId, runId, turnId, round, res));
        // L4 长期记忆：run 结束后反思抽取，把本轮对话蒸馏成可检索的长期记忆
        LongTermMemory.get().reflectAndStore(sessionId, agentState.getModelContext(), new ModelSummarizer(model));
        runTrace.setEndTime(System.currentTimeMillis());
        runTrace.printTraceByRunId(runId);
        log.info("Run finished: {}", tokenTracker.summary());
        return res;

    }

    private String getTurnName(int turnId) {
        return "turn_" + turnId;
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
            log.info("[{}] {} | time: {} | sessionId: {} | turnId: {}",
                    i + 1,
                    event.getType(),
                    event.getTimestamp(),
                    event.getSessionId(),
                    event.getTurnId());
        }
        log.info("===== End Event Log =====");
    }
}
