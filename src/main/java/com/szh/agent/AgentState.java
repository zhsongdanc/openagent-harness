package com.szh.agent;

import com.szh.context.InstructionMemoryLoader;
import com.szh.context.dto.AssistantMessageItem;
import com.szh.context.dto.MessageItem;
import com.szh.context.dto.SystemMessageItem;
import com.szh.context.dto.ToolMessageItem;
import com.szh.event.Event;
import com.szh.event.MessageEvent;
import com.szh.store.EventStore;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * @author demussong
 * @describe
 * @date 2026/8/25 12:08
 */
@Slf4j
public class AgentState {

    // 分层指令记忆装配出的 system prompt（全局 ~/.openagent/AGENTS.md + 目录链 AGENTS.md，含 @import）
    private final String systemPrompt;

    // 给到大模型的窗口（运行期增量维护；恢复时由 deriveMessages 从事件日志重建）
    private List<MessageItem> modelContext = new ArrayList<>();

    private EventStore eventStore;

    private int turnId = 0;

    public AgentState(EventStore eventStore) {
        this.eventStore = eventStore;
        this.systemPrompt = InstructionMemoryLoader.loadSystemPrompt();
        modelContext.add(new SystemMessageItem(systemPrompt));
    }

    public List<MessageItem> getModelContext() {
        return modelContext;
    }

    /**
     * 压缩后替换整个 modelContext（保留 system prompt 位置）
     */
    public void replaceModelContext(List<MessageItem> newContext) {
        log.info("AgentState: replacing modelContext, size {} -> {}", modelContext.size(), newContext.size());
        this.modelContext = newContext;
    }

    /**
     * 事件落库 + 上下文增量维护。并行工具执行时多个 worker 线程可能同时回调，
     * modelContext 是普通 ArrayList，必须同步保护「落库 + 追加」的原子性
     */
    public synchronized void applyEvent(Event event) {
        // 事件日志是唯一真相源，先落库
        boolean durable = true;
        try {
            eventStore.appendEvent(event);
        } catch (RuntimeException e) {
            durable = false;
            log.error("append event failed, type={}, eventId={}, sessionId={}",
                    event.getType(), event.getId(), event.getSessionId(), e);
        }
        // 运行期增量维护上下文，避免每轮重读全量日志
        if (durable && event instanceof MessageEvent messageEvent) {
            modelContext.add(messageEvent.getMessageItem());
        }
    }

    /**
     * 断点恢复：从事件日志重建模型上下文。上下文唯一真相源是事件流，
     * 只有 surface 事件（MessageEvent）会进入模型历史。
     */
    public void resume(String sessionId) {
        List<Event> events = new ArrayList<>(eventStore.getEvents(sessionId));
        events.sort(Comparator.comparingLong(Event::getTimestamp));
        this.modelContext = trimIncompleteTail(deriveMessages(events));
    }

    /**
     * 事件日志按前缀截断是允许的（进程崩溃/写库失败），但半截 step 会让重建出的
     * history 以未应答的 tool_calls 结尾，模型侧直接报错。这里从尾部裁掉未闭合的一步。
     *
     *
     */
    private List<MessageItem> trimIncompleteTail(List<MessageItem> context) {
        for (int i = context.size() - 1; i >= 0; i--) {
            if (context.get(i) instanceof AssistantMessageItem assistant && assistant.isCallTool()) {
                String callId = assistant.getToolCallId();
                boolean answered = false;
                for (int j = i + 1; j < context.size(); j++) {
                    if (context.get(j) instanceof ToolMessageItem tool
                            && Objects.equals(tool.getCallId(), callId)) {
                        answered = true;
                        break;
                    }
                }
                // 最近一次工具调用没有对应结果 => 该 step 未闭合，从它之前截断
                return answered ? context : new ArrayList<>(context.subList(0, i));
            }
        }
        return context;
    }

    private List<MessageItem> deriveMessages(List<Event> events) {
        List<MessageItem> context = new ArrayList<>();
        context.add(new SystemMessageItem(systemPrompt));
        for (Event event : events) {
            if (event instanceof MessageEvent messageEvent) {
                context.add(messageEvent.getMessageItem());
            }
        }
        return context;
    }

    public int getTurnId() {
        return turnId;
    }

    public void incrementTurnId() {
        this.turnId++;
    }

    public EventStore getEventStore() {
        return eventStore;
    }

}
