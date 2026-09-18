package com.szh.trace;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * @author demussong
 * @describe 一次对话的执行轨迹
 * @date 2026/8/31 14:53
 */
@Slf4j
@AllArgsConstructor
@NoArgsConstructor
@Data
public class RunTrace {

    private String runId;

    private String sessionId;

    private long startTime;

    private long endTime;

    public RunTrace(String runId, String sessionId, long startTime) {
        this.runId = runId;
        this.sessionId = sessionId;
        this.startTime = startTime;
        this.stepTraceList = new ArrayList<>();
    }

    private List<StepTrace> stepTraceList;

    private TokenTracker tokenTracker;

    public void setTokenTracker(TokenTracker tokenTracker) {
        this.tokenTracker = tokenTracker;
    }

    public void printTraceByRunId(String runId) {
        if (!this.runId.equals(runId)) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("RunTrace [runId=").append(runId)
                .append(", sessionId=").append(sessionId)
                .append(", startTime=").append(startTime)
                .append(", endTime=").append(endTime)
                .append("]\n");
        if (stepTraceList != null && !stepTraceList.isEmpty()) {
            sb.append("  Steps:\n");
            for (StepTrace step : stepTraceList) {
                sb.append("    Step ").append(step.getStepNum())
                        .append(" [startTime=").append(step.getStartTime())
                        .append(", endTime=").append(step.getEndTime());
                if (step.getTokenUsage() != null) {
                    sb.append(", tokens=").append(step.getTokenUsage().getTotalTokens())
                            .append("(prompt=").append(step.getTokenUsage().getPromptTokens())
                            .append(", completion=").append(step.getTokenUsage().getCompletionTokens())
                            .append(")");
                }
                sb.append("]\n");
            }
        } else {
            sb.append("  No steps recorded.\n");
        }
        if (tokenTracker != null) {
            sb.append("  ").append(tokenTracker.summary()).append("\n");
        }

        log.info("printTraceByRunId {}", sb.toString());
    }

    public void addStepTrace(StepTrace stepTrace) {
        stepTraceList.add(stepTrace);
    }
}
