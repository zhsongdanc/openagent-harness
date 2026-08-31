package com.szh.trace;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author demussong
 * @describe 一个步骤的执行轨迹
 * @date 2026/8/31 14:53
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class StepTrace {

    private int stepNum;

    private long startTime;

    private long endTime;
}
