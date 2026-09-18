package com.szh.trace;

import com.szh.model.dto.TokenUsage;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author demussong
 * @describe 一个步骤执行轨迹
 * @date 2026/8/31 14:53
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class StepTrace {

    private int stepNum;

    private long startTime;

    private long endTime;

    /**
     * 本轮 token 用量（服务端返回）
     */
    private TokenUsage tokenUsage;

    public StepTrace(int stepNum, long startTime, long endTime) {
        this.stepNum = stepNum;
        this.startTime = startTime;
        this.endTime = endTime;
    }
}
