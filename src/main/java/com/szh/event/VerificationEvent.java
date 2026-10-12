package com.szh.event;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 验证闭环事件：每次验证闸门跑完编译/测试命令后落一条，记录通过与否、失败命令与退出码。
 * <p>
 * 与 {@link TodoUpdatedEvent}/{@link ModeSwitchedEvent} 同理，是面向用户/回放的 surface 事件——
 * 刻意<b>不</b>继承 {@link MessageEvent}、不进 modelContext（验证结果已通过回灌的 user 消息或最终答复给到模型，
 * 再塞进上下文既冗余又会破坏 Responses API 分组约束）。故 {@code AgentState.resume()} 天然忽略它，
 * 只由 REPL / {@code TraceReplay} 消费。
 *
 * @author demussong
 * @date 2026/10/9
 */
public class VerificationEvent extends Event {

    private final boolean passed;

    /**
     * 失败命令；通过时为 null
     */
    private final String command;

    private final int exitCode;

    /**
     * 失败附注（被拦截/超时/异常等原因），无则 null
     */
    private final String detail;

    public VerificationEvent(String sessionId, String runId, String turnId, int round,
                             boolean passed, String command, int exitCode, String detail) {
        super();
        this.sessionId = sessionId;
        this.runId = runId;
        this.turnId = turnId;
        this.round = round;
        this.passed = passed;
        this.command = command;
        this.exitCode = exitCode;
        this.detail = detail;
    }

    public boolean isPassed() {
        return passed;
    }

    public String getCommand() {
        return command;
    }

    public int getExitCode() {
        return exitCode;
    }

    public String getDetail() {
        return detail;
    }

    @Override
    public EventEnum getType() {
        return EventEnum.VERIFICATION;
    }

    @Override
    public Object payloadData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("passed", passed);
        data.put("command", command);
        data.put("exitCode", exitCode);
        data.put("detail", detail);
        return data;
    }
}
