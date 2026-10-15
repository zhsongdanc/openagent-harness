package com.szh.web;

import com.szh.context.dto.AssistantMessageItem;
import com.szh.context.dto.MessageItem;
import com.szh.context.dto.ReasoningMessageItem;
import com.szh.context.dto.ToolMessageItem;
import com.szh.context.dto.UserMessageItem;
import com.szh.event.CallToolFinishedEvent;
import com.szh.event.CallToolStartedEvent;
import com.szh.event.ContextCompactedEvent;
import com.szh.event.Event;
import com.szh.event.EventEnum;
import com.szh.event.ModeSwitchedEvent;
import com.szh.event.ModelResponseEvent;
import com.szh.event.ReasoningEvent;
import com.szh.event.RunCompletedEvent;
import com.szh.event.RunStartedEvent;
import com.szh.event.TokenUsageEvent;
import com.szh.event.TodoUpdatedEvent;
import com.szh.event.UserMessageEvent;
import com.szh.event.VerificationEvent;
import com.szh.model.dto.ToolCall;
import com.szh.tool.tools.meta.TodoItem;
import com.szh.utils.JsonUtil;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 把某会话的事件流折叠成「turn → run → round → 步骤」的展示模型（纯只读、无副作用）。
 * <p>
 * 前端刻意做成「哑」的：所有分组、配对、耗时计算都在这里完成，页面只负责渲染。理由是这个模型
 * 同时被三类消费者使用（网页时间线、将来的导出、单测断言），逻辑必须只有一份。
 * <p>
 * 与 {@code TraceReplay} 的区别：回放器输出的是「给人看的文本/HTML」，这里输出的是「结构化 JSON」——
 * 粒度到轮次，且带上轮次级的 token / 上下文规模 / 工具调用配对，便于页面按轮下钻。
 *
 * @author demussong
 * @date 2026/10/15
 */
public class SessionViewBuilder {

    /**
     * 构建会话详情视图
     *
     * @param sessionId 会话 id
     * @param events    已按时间升序的事件流
     * @param snapshots 快照读侧：用于标注每轮上下文是「精确快照」还是「事件流重建」
     */
    public Map<String, Object> build(String sessionId, List<Event> events, ContextSnapshotReader snapshots) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("sessionId", sessionId);
        root.put("summary", summary(events));

        // turnId -> runId -> 该轮事件（保持出现顺序，用 LinkedHashMap）
        Map<String, Map<String, List<Event>>> turns = new LinkedHashMap<>();
        for (Event event : events) {
            String turnId = event.getTurnId() == null ? "turn_0" : event.getTurnId();
            String runId = event.getRunId() == null ? "run_unknown" : event.getRunId();
            turns.computeIfAbsent(turnId, k -> new LinkedHashMap<>())
                    .computeIfAbsent(runId, k -> new ArrayList<>())
                    .add(event);
        }

        List<Map<String, Object>> turnViews = new ArrayList<>();
        for (Map.Entry<String, Map<String, List<Event>>> turnEntry : turns.entrySet()) {
            Map<String, Object> turnView = new LinkedHashMap<>();
            turnView.put("turnId", turnEntry.getKey());
            List<Map<String, Object>> runViews = new ArrayList<>();
            long turnStart = Long.MAX_VALUE;
            long turnEnd = 0L;
            long turnTokens = 0L;
            long turnTokenRounds = 0L;
            for (Map.Entry<String, List<Event>> runEntry : turnEntry.getValue().entrySet()) {
                Map<String, Object> runView = buildRun(runEntry.getKey(), runEntry.getValue(), snapshots);
                runViews.add(runView);
                turnStart = Math.min(turnStart, num(runView.get("startTime")));
                turnEnd = Math.max(turnEnd, num(runView.get("endTime")));
                turnTokens += num(runView.get("totalTokens"));
                turnTokenRounds += num(runView.get("tokenRounds"));
            }
            turnView.put("runs", runViews);
            turnView.put("startTime", turnStart == Long.MAX_VALUE ? 0L : turnStart);
            turnView.put("endTime", turnEnd);
            turnView.put("durationMs", Math.max(0L, turnEnd - (turnStart == Long.MAX_VALUE ? turnEnd : turnStart)));
            turnView.put("totalTokens", turnTokens);
            turnView.put("tokenRounds", turnTokenRounds);
            turnView.put("userInput", firstUserInput(turnEntry.getValue()));
            turnViews.add(turnView);
        }
        root.put("turns", turnViews);
        return root;
    }

    // ==================== run ====================

    private Map<String, Object> buildRun(String runId, List<Event> events, ContextSnapshotReader snapshots) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("runId", runId);
        view.put("turnId", events.isEmpty() ? null : events.get(0).getTurnId());
        long start = events.isEmpty() ? 0L : events.get(0).getTimestamp();
        long end = events.isEmpty() ? 0L : events.get(events.size() - 1).getTimestamp();
        view.put("startTime", start);
        view.put("endTime", end);
        view.put("durationMs", Math.max(0L, end - start));

        // 该 run 是否存在快照文件（一次 stat + 一次解析，避免逐轮读文件）
        String sessionId = events.isEmpty() ? null : events.get(0).getSessionId();
        List<Integer> snapshotRounds = (snapshots == null || sessionId == null) ? List.of()
                : new ArrayList<>(snapshots.rounds(sessionId, runId));

        String finalAnswer = null;
        long totalTokens = 0L;
        int promptTokens = 0;
        int completionTokens = 0;
        int cachedTokens = 0;
        int tokenRounds = 0;

        // round -> 该轮事件（保持顺序）
        Map<Integer, List<Event>> rounds = new LinkedHashMap<>();
        for (Event event : events) {
            rounds.computeIfAbsent(event.getRound(), k -> new ArrayList<>()).add(event);
            if (event instanceof RunCompletedEvent completed) {
                finalAnswer = completed.getResult();
            }
            if (event instanceof TokenUsageEvent t) {
                tokenRounds++;
                totalTokens += t.getTotalTokens();
                promptTokens += t.getPromptTokens();
                completionTokens += t.getCompletionTokens();
                cachedTokens += t.getCachedTokens();
            }
        }

        List<Map<String, Object>> roundViews = new ArrayList<>();
        for (Map.Entry<Integer, List<Event>> entry : rounds.entrySet()) {
            roundViews.add(buildRound(entry.getKey(), entry.getValue(), snapshotRounds));
        }
        view.put("rounds", roundViews);
        view.put("roundCount", roundViews.size());
        view.put("finalAnswer", finalAnswer);
        view.put("totalTokens", totalTokens);
        view.put("promptTokens", promptTokens);
        view.put("completionTokens", completionTokens);
        view.put("cachedTokens", cachedTokens);
        // 记录过 token 的轮数：用于区分「真就是 0」与「本功能上线前的会话（未记录）」
        view.put("tokenRounds", tokenRounds);
        return view;
    }

    // ==================== round ====================

    private Map<String, Object> buildRound(int round, List<Event> events, List<Integer> snapshotRounds) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("round", round);
        long start = events.isEmpty() ? 0L : events.get(0).getTimestamp();
        long end = events.isEmpty() ? 0L : events.get(events.size() - 1).getTimestamp();
        view.put("startTime", start);
        view.put("endTime", end);
        view.put("durationMs", Math.max(0L, end - start));

        TokenUsageEvent usage = null;
        for (Event event : events) {
            if (event instanceof TokenUsageEvent t) {
                usage = t;
            }
        }
        view.put("modelMs", usage == null ? null : usage.getElapsedMs());
        view.put("tokens", usage == null ? null : tokens(usage));
        view.put("contextSize", usage == null ? null : contextSize(usage));
        view.put("hasSnapshot", snapshotRounds.contains(round));
        view.put("tools", toolCalls(events));
        view.put("steps", steps(events));
        return view;
    }

    private Map<String, Object> tokens(TokenUsageEvent usage) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("promptTokens", usage.getPromptTokens());
        data.put("completionTokens", usage.getCompletionTokens());
        data.put("totalTokens", usage.getTotalTokens());
        data.put("cachedTokens", usage.getCachedTokens());
        return data;
    }

    private Map<String, Object> contextSize(TokenUsageEvent usage) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("messages", usage.getContextMessages());
        data.put("estimatedTokens", usage.getContextEstimatedTokens());
        return data;
    }

    /**
     * 本轮工具调用配对：模型的 tool_calls（权威的 callId/参数）与工具执行结果（按 callId 配对）。
     * 旧日志里 assistant 消息可能只有标量工具字段，故再兜一层 CALL_TOOL_STARTED 的按序配对。
     */
    private List<Map<String, Object>> toolCalls(List<Event> events) {
        List<ToolCall> calls = new ArrayList<>();
        for (Event event : events) {
            if (event instanceof ModelResponseEvent modelEvent
                    && modelEvent.getMessageItem() instanceof AssistantMessageItem assistant) {
                calls.addAll(assistant.effectiveToolCalls());
            }
        }
        Map<String, String> outputByCallId = new LinkedHashMap<>();
        List<CallToolFinishedEvent> finished = new ArrayList<>();
        List<CallToolStartedEvent> started = new ArrayList<>();
        for (Event event : events) {
            if (event instanceof CallToolFinishedEvent f) {
                finished.add(f);
                if (f.getMessageItem() instanceof ToolMessageItem tool && tool.getCallId() != null) {
                    outputByCallId.put(tool.getCallId(), tool.getExecResult());
                }
            } else if (event instanceof CallToolStartedEvent s) {
                started.add(s);
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < calls.size(); i++) {
            ToolCall call = calls.get(i);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("callId", call.toolCallId());
            item.put("code", call.toolCode());
            item.put("args", call.toolArgs());
            item.put("output", call.toolCallId() == null ? null : outputByCallId.get(call.toolCallId()));
            out.add(item);
        }
        if (out.isEmpty()) {
            for (int i = 0; i < started.size(); i++) {
                CallToolStartedEvent s = started.get(i);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("callId", i < finished.size() && finished.get(i).getMessageItem() instanceof ToolMessageItem t
                        ? t.getCallId() : null);
                item.put("code", s.getToolName());
                item.put("args", s.getParameters());
                item.put("output", i < finished.size() ? finished.get(i).getOutput() : null);
                out.add(item);
            }
        }
        return out;
    }

    /**
     * 本轮的有序步骤：前端按 kind 渲染不同卡片
     */
    private List<Map<String, Object>> steps(List<Event> events) {
        List<Map<String, Object>> steps = new ArrayList<>();
        for (Event event : events) {
            Map<String, Object> step = step(event);
            if (step != null) {
                steps.add(step);
            }
        }
        return steps;
    }

    private Map<String, Object> step(Event event) {
        EventEnum type = event.getType();
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("type", type.name());
        step.put("time", event.getTimestamp());
        switch (type) {
            case RUN_STARTED -> {
                step.put("kind", "runStarted");
            }
            case USER_INPUT -> {
                step.put("kind", "user");
                step.put("text", event instanceof UserMessageEvent u
                        && u.getMessageItem() instanceof UserMessageItem item ? item.getContent() : null);
            }
            case MODEL_REASONING -> {
                step.put("kind", "reasoning");
                step.put("text", event instanceof ReasoningEvent r
                        && r.getMessageItem() instanceof ReasoningMessageItem item ? item.getContent() : null);
            }
            case CALL_MODEL_FINISHED -> {
                step.put("kind", "model");
                if (event instanceof ModelResponseEvent m && m.getMessageItem() instanceof AssistantMessageItem a) {
                    step.put("text", a.getContent());
                    step.put("toolCalls", a.effectiveToolCalls().stream().map(c -> {
                        Map<String, Object> call = new LinkedHashMap<>();
                        call.put("id", c.toolCallId());
                        call.put("code", c.toolCode());
                        call.put("args", c.toolArgs());
                        return call;
                    }).toList());
                }
            }
            case TOKEN_USAGE -> {
                step.put("kind", "tokens");
                if (event instanceof TokenUsageEvent t) {
                    step.put("tokens", tokens(t));
                    step.put("contextSize", contextSize(t));
                    step.put("elapsedMs", t.getElapsedMs());
                }
            }
            case CALL_TOOL_STARTED -> {
                step.put("kind", "toolStarted");
                if (event instanceof CallToolStartedEvent s) {
                    step.put("code", s.getToolName());
                    step.put("args", s.getParameters());
                }
            }
            case CALL_TOOL_FINISHED -> {
                step.put("kind", "toolDone");
                if (event instanceof CallToolFinishedEvent f && f.getMessageItem() instanceof ToolMessageItem tool) {
                    step.put("callId", tool.getCallId());
                    step.put("code", tool.getToolCode());
                    step.put("output", tool.getExecResult());
                }
            }
            case VERIFICATION -> {
                step.put("kind", "verify");
                if (event instanceof VerificationEvent v) {
                    step.put("passed", v.isPassed());
                    step.put("command", v.getCommand());
                    step.put("exitCode", v.getExitCode());
                    step.put("detail", v.getDetail());
                }
            }
            case CONTEXT_COMPACTED -> {
                step.put("kind", "compact");
                if (event instanceof ContextCompactedEvent c) {
                    step.put("summary", c.getSummary());
                    step.put("keepRecentCount", c.getKeepRecentCount());
                    step.put("beforeMessages", c.getBeforeMessages());
                    step.put("afterMessages", c.getAfterMessages());
                }
            }
            case TODO_UPDATED -> {
                step.put("kind", "todo");
                if (event instanceof TodoUpdatedEvent t) {
                    step.put("todos", t.getTodos().stream().map(this::todo).toList());
                }
            }
            case MODE_SWITCHED -> {
                step.put("kind", "mode");
                step.put("mode", event instanceof ModeSwitchedEvent m ? m.getMode() : null);
            }
            case RUN_COMPLETED -> {
                step.put("kind", "runCompleted");
                step.put("result", event instanceof RunCompletedEvent r ? r.getResult() : null);
            }
            default -> {
                step.put("kind", "other");
                step.put("payload", JsonUtil.toJson(event.payloadData()));
            }
        }
        return step;
    }

    private Map<String, Object> todo(TodoItem item) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", item.getId());
        data.put("content", item.getContent());
        data.put("status", item.safeStatus() == null ? null : item.safeStatus().name());
        return data;
    }

    // ==================== 会话级汇总 ====================

    private Map<String, Object> summary(List<Event> events) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("events", events.size());
        summary.put("runs", (int) events.stream().map(Event::getRunId).filter(Objects::nonNull).distinct().count());
        summary.put("turns", (int) events.stream().map(Event::getTurnId).filter(Objects::nonNull).distinct().count());
        long start = events.isEmpty() ? 0L : events.get(0).getTimestamp();
        long end = events.isEmpty() ? 0L : events.get(events.size() - 1).getTimestamp();
        summary.put("startTime", start);
        summary.put("endTime", end);
        long total = 0L;
        long prompt = 0L;
        long completion = 0L;
        long cached = 0L;
        long tokenRounds = 0L;
        for (Event event : events) {
            if (event instanceof TokenUsageEvent t) {
                tokenRounds++;
                total += t.getTotalTokens();
                prompt += t.getPromptTokens();
                completion += t.getCompletionTokens();
                cached += t.getCachedTokens();
            }
        }
        summary.put("totalTokens", total);
        summary.put("promptTokens", prompt);
        summary.put("completionTokens", completion);
        summary.put("cachedTokens", cached);
        // 哪些会话真的记录了 token、哪些只是老会话（前端据此渲染 0 或「未记录」，不能拿数值判真假）
        summary.put("tokenRounds", tokenRounds);
        return summary;
    }

    /**
     * turn 的第一条用户输入（run 内最先落库的 USER_INPUT），列表与详情页都用它标识这一轮问了什么；
     * 验证闸门回灌的 USER_INPUT 是后续轮次的，不会影响取值
     */
    private String firstUserInput(Map<String, List<Event>> runs) {
        for (List<Event> events : runs.values()) {
            for (Event event : events) {
                if (event instanceof UserMessageEvent u && u.getMessageItem() instanceof UserMessageItem item) {
                    return item.getContent();
                }
            }
        }
        return null;
    }

    private static long num(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }
}
