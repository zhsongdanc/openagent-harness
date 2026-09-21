package com.szh.event;

/**
 * 运行模式切换事件：{@code switch_mode} 元工具（或 REPL {@code /mode}）切换 NORMAL/PLAN 时落一条。
 * <p>
 * 与 {@link TodoUpdatedEvent} 同理，是面向用户/运行时的 surface 事件，不继承 {@link MessageEvent}、
 * 不进 modelContext；PLAN 模式的实际约束（收敛只读工具 + 注入规划指令）由运行时每轮读
 * {@code AgentModeStore} 施加，本事件只负责留痕、供 {@code /session} 恢复时回灌模式与回放展示。
 *
 * @author demussong
 * @date 2026/9/22
 */
public class ModeSwitchedEvent extends Event {

    /**
     * 切换后的模式名（{@code AgentMode} 枚举名：NORMAL / PLAN）
     */
    private final String mode;

    public ModeSwitchedEvent(String sessionId, String runId, String turnId, int round, String mode) {
        super();
        this.sessionId = sessionId;
        this.runId = runId;
        this.turnId = turnId;
        this.round = round;
        this.mode = mode;
    }

    public String getMode() {
        return mode;
    }

    @Override
    public EventEnum getType() {
        return EventEnum.MODE_SWITCHED;
    }

    @Override
    public Object payloadData() {
        return mode;
    }
}
