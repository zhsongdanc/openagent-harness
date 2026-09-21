package com.szh.tool.tools.meta;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话级运行模式存储（进程内单例）。{@code switch_mode} 工具写、两条运行时每轮读取以决定是否收敛工具集，
 * REPL {@code /mode} 命令读写；{@code /session} 恢复时由 REPL 从 {@code ModeSwitchedEvent} 回灌。
 * <p>
 * 未设置过的 session 默认 {@link AgentMode#NORMAL}。
 *
 * @author demussong
 * @date 2026/9/22
 */
public final class AgentModeStore {

    private static final AgentModeStore INSTANCE = new AgentModeStore();

    public static AgentModeStore get() {
        return INSTANCE;
    }

    private final Map<String, AgentMode> modes = new ConcurrentHashMap<>();

    private AgentModeStore() {
    }

    public AgentMode get(String sessionId) {
        return sessionId == null ? AgentMode.NORMAL : modes.getOrDefault(sessionId, AgentMode.NORMAL);
    }

    public boolean isPlan(String sessionId) {
        return get(sessionId) == AgentMode.PLAN;
    }

    public void set(String sessionId, AgentMode mode) {
        if (sessionId == null) {
            return;
        }
        modes.put(sessionId, mode == null ? AgentMode.NORMAL : mode);
    }

    public void clear(String sessionId) {
        if (sessionId != null) {
            modes.remove(sessionId);
        }
    }
}
