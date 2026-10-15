package com.szh.agent;

import com.szh.context.InstructionMemoryLoader;
import com.szh.context.dto.AssistantMessageItem;
import com.szh.context.dto.MessageItem;
import com.szh.context.dto.SystemMessageItem;
import com.szh.context.dto.ToolMessageItem;
import com.szh.event.Event;
import com.szh.event.ContextCompactedEvent;
import com.szh.event.MessageEvent;
import com.szh.store.EventStore;
import com.szh.utils.CommonUtils;
import com.szh.utils.ConfigUtil;
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
        this(eventStore, null);
    }

    /**
     * 带角色指令的构造：在分层 system prompt 之后追加一段角色契约。
     * <p>
     * 子 agent（{@code com.szh.agent.subagent.SubAgentExecutor}）用它注入「你是子 agent、独立上下文、
     * 只回最终结论」的角色指令，既保留项目 AGENTS.md 约定，又叠加子 agent 专属身份。appendix 为空时
     * 行为与无参构造完全一致。项目层是否加载由配置 {@code memory.instruction.project.enabled} 决定。
     */
    public AgentState(EventStore eventStore, String rolePromptAppendix) {
        this(eventStore, rolePromptAppendix, ConfigUtil.getBoolean("memory.instruction.project.enabled", true));
    }

    /**
     * 同时控制“带不带角色指令”与“带不带项目层指令记忆”。
     * <p>
     * {@code projectMemory=false} 时只装配内置基础 prompt（+ 全局层）再加角色指令——给纯检索型子 agent 用：
     * 它只负责定位代码、不回写仓库，项目 internals 对它是每轮都要重付的无效常驻预算（派生 N 个就是 N 份）。
     * 注意角色指令仍追加在最末，保证基础 prompt 构成的前缀稳定、不破坏模型侧 prompt cache。
     */
    public AgentState(EventStore eventStore, String rolePromptAppendix, boolean projectMemory) {
        this.eventStore = eventStore;
        String base = InstructionMemoryLoader.loadSystemPrompt(projectMemory);
        this.systemPrompt = (rolePromptAppendix == null || rolePromptAppendix.isBlank())
                ? base
                : base + "\n\n" + rolePromptAppendix;
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
     * 回溯恢复：只重放轮次序号 {@code < upToTurnSeq} 的事件，把上下文重建到「第 upToTurnSeq 轮之前」，
     * 即撤销第 upToTurnSeq 轮及其之后的全部对话。与 {@code FileSnapshotStore.restoreToTurn} 的文件还原
     * 配套使用，保证「上下文回退」与「文件回退」落在同一时刻，不脱节。
     * <p>
     * 同时把 turnId 计数回拨到 {@code upToTurnSeq - 1}，使续跑的下一轮复用被撤销的轮次号，
     * 与快照目录命名保持连续。调用方需另行截断事件日志（{@code EventStore.truncateFromTurn}）
     * 以保证持久化引擎下不会残留被撤销轮次的事件。
     */
    public void resume(String sessionId, int upToTurnSeq) {
        List<Event> events = new ArrayList<>(eventStore.getEvents(sessionId));
        events.sort(Comparator.comparingLong(Event::getTimestamp));
        // 丢弃目标轮次及之后的事件（turnId 无法解析时序号记 0，天然保留）
        events.removeIf(e -> CommonUtils.parseTurnSeq(e.getTurnId()) >= upToTurnSeq);
        this.modelContext = trimIncompleteTail(deriveMessages(events));
        this.turnId = Math.max(0, upToTurnSeq - 1);
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
            } else if (event instanceof ContextCompactedEvent compactedEvent) {
                // 压缩检查点：把此前累积的历史折叠为「摘要 + 尾部若干条」，
                // 直接重建到运行期压缩后状态，无需再调 LLM 重压
                context = collapseCompacted(context, compactedEvent);
            }
        }
        return context;
    }

    /**
     * 应用压缩检查点：把已累积的 {@code [system, m1..mN]} 折叠为
     * {@code [system, summary, 尾部 keepRecentCount 条]}，与运行期 {@code LlmCompactor} 产物结构一致。
     * <p>
     * 尾部按条数截取即可逐字还原：L0 保留最近 KEEP_RECENT 条不变、L1 的 recentMessages 就是这批原文，
     * 事件日志里对应的 MessageEvent 保真度相同。多次压缩时本方法按事件顺序增量叠加，后一个检查点
     * 会在前一个的折叠结果上继续折叠。summary 为 null（异常兜底）时原样返回，不改变上下文。
     */
    private List<MessageItem> collapseCompacted(List<MessageItem> context, ContextCompactedEvent compactedEvent) {
        String summary = compactedEvent.getSummary();
        if (summary == null || context.isEmpty()) {
            return context;
        }
        MessageItem system = context.get(0);
        int bodySize = context.size() - 1;
        int keep = Math.max(0, Math.min(compactedEvent.getKeepRecentCount(), bodySize));
        List<MessageItem> rebuilt = new ArrayList<>();
        rebuilt.add(system);
        rebuilt.add(new SystemMessageItem(summary));
        rebuilt.addAll(context.subList(context.size() - keep, context.size()));
        log.info("AgentState: 应用压缩检查点，上下文 {} -> {} 条（保留尾部 {} 条）",
                context.size(), rebuilt.size(), keep);
        return rebuilt;
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
