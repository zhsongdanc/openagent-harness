package com.szh.memory;

import com.szh.utils.ConfigUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * L4 长期记忆「反思抽取」的触发策略。
 * <p>
 * 背景：早期实现是每个 run（REPL 里每一次用户对话轮）结束都无条件跑一次 LLM 蒸馏，
 * 成本高、噪音大且卡在返回路径上。对标主流 harness：
 * <ul>
 *   <li>Claude Code Auto Memory：由主模型选择性判断，提取时机绑定「上下文压缩前」；</li>
 *   <li>Hermes：{@code on_pre_compress} 抢救 + Background Review 异步 + 每 N 任务 Periodic Nudge。</li>
 * </ul>
 * 因此这里把「每轮」降级为可配置的多档触发：
 * <ul>
 *   <li>{@code off}：从不自动反思，完全依赖模型主动调 remember 工具；</li>
 *   <li>{@code every_run}：兼容旧行为，每个 run 结束都反思；</li>
 *   <li>{@code on_compaction}：仅当本 run 真的触发了上下文压缩时反思（信息即将被丢弃才沉淀，默认）；</li>
 *   <li>{@code interval}：每累计 N 个 turn 反思一次（对标 Periodic Nudge）；</li>
 *   <li>{@code session_end}：run 期间不反思，改由会话结束（退出/切换）时统一蒸馏一次。</li>
 * </ul>
 * 另有一条与上述门控正交的「压缩抢救」通道（{@link #shouldRescueOnCompaction()}）：只要真的
 * 发生了压缩，被裁掉/截断替换的原文都会单独蒸馏一次——run 末反思拿到的已是压缩后上下文，
 * 不抢救则摘要未保留的信息永久丢失。仅 {@code off} 会连同抢救一起关闭。
 * 该策略只做「是否触发」的判定，实际的异步执行与落库由 {@link LongTermMemory} 负责。
 *
 * @author demussong
 * @date 2026/9/23
 */
@Slf4j
public class ReflectionPolicy {

    /** 触发模式 */
    public enum Trigger {
        OFF, EVERY_RUN, ON_COMPACTION, INTERVAL, SESSION_END;

        /** 宽松解析：未知/缺省回退 ON_COMPACTION（省成本又不丢关键沉淀时机） */
        static Trigger from(String s) {
            if (s == null || s.isBlank()) {
                return ON_COMPACTION;
            }
            switch (s.trim().toLowerCase()) {
                case "off":
                case "none":
                case "disable":
                case "disabled":
                    return OFF;
                case "every_run":
                case "every":
                case "run":
                    return EVERY_RUN;
                case "on_compaction":
                case "compaction":
                case "compact":
                    return ON_COMPACTION;
                case "interval":
                case "periodic":
                case "nudge":
                    return INTERVAL;
                case "session_end":
                case "on_session_end":
                case "session":
                    return SESSION_END;
                default:
                    log.warn("ReflectionPolicy: unknown trigger '{}', fallback to ON_COMPACTION", s);
                    return ON_COMPACTION;
            }
        }
    }

    private final Trigger trigger;
    private final int intervalTurns;

    /** interval 模式下按 session 累计 turn 数；进程内即可，重启归零无副作用 */
    private final Map<String, AtomicInteger> turnCounters = new ConcurrentHashMap<>();

    public ReflectionPolicy() {
        this.trigger = Trigger.from(ConfigUtil.get("memory.reflection.trigger", "on_compaction"));
        this.intervalTurns = Math.max(1, ConfigUtil.getInt("memory.reflection.intervalTurns", 5));
        log.info("ReflectionPolicy: trigger={}, intervalTurns={}", trigger, intervalTurns);
    }

    public Trigger getTrigger() {
        return trigger;
    }

    /**
     * run 结束时是否应触发反思。
     * <p>{@code SESSION_END} 恒返回 false（延迟到会话结束统一触发）；
     * {@code INTERVAL} 会顺带累加该 session 的 turn 计数。
     *
     * @param sessionId          会话 id（interval 计数用）
     * @param compactionHappened 本 run 是否真的执行了上下文压缩
     */
    public boolean shouldReflectOnRunEnd(String sessionId, boolean compactionHappened) {
        switch (trigger) {
            case EVERY_RUN:
                return true;
            case ON_COMPACTION:
                return compactionHappened;
            case INTERVAL:
                return counter(sessionId).incrementAndGet() % intervalTurns == 0;
            case OFF:
            case SESSION_END:
            default:
                return false;
        }
    }

    /** 会话结束是否应触发反思：仅 {@code SESSION_END} 模式返回 true */
    public boolean shouldReflectOnSessionEnd() {
        return trigger == Trigger.SESSION_END;
    }

    /**
     * 压缩丢弃内容是否应抢救蒸馏：除 {@code OFF}（彻底关闭自动反思）外都应执行。
     * <p>被压缩裁掉的消息在此之后任何反思时机（run 末/会话末）都无法再从上下文看到，
     * 属于不可逆信息丢失，因此不受「每 N turn / 会话结束」等节流门控，只尊重总开关。
     */
    public boolean shouldRescueOnCompaction() {
        return trigger != Trigger.OFF;
    }

    private AtomicInteger counter(String sessionId) {
        return turnCounters.computeIfAbsent(sessionId == null ? "" : sessionId, k -> new AtomicInteger());
    }
}
