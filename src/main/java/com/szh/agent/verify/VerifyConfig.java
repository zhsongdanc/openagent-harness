package com.szh.agent.verify;

import lombok.Data;

import java.util.List;

/**
 * 验证闭环配置：声明「改动代码后要跑哪些验证命令、失败后最多修复几轮」。
 * <p>
 * 来源分层：{@code application.properties} 提供调优旋钮（开关/轮次/超时/触发条件）作为基线，
 * 项目级 {@code {workspace}/.openagent/verify.json} 或全局 {@code ~/.openagent/verify.json} 覆盖之并提供
 * 验证命令清单。由 {@link VerifyConfigLoader} 装配。
 *
 * @author demussong
 * @date 2026/10/9
 */
@Data
public class VerifyConfig {

    /**
     * 总开关：关闭时验证闸门完全旁路
     */
    private boolean enabled = false;

    /**
     * 验证命令清单，按顺序执行、fail-fast（首个失败即停并回灌）。
     * 每条为完整命令字符串，执行前按空白拆成 argv（不经 shell 解释器，无注入风险）。
     */
    private List<String> commands = List.of();

    /**
     * 验证-修复循环上限：模型每次宣布完成若验证不过，最多逼其重修几轮
     */
    private int maxFixRounds = 3;

    /**
     * 单条验证命令超时（秒）
     */
    private long timeoutSeconds = 600;

    /**
     * 仅当本 run 改过文件才验证：纯问答/只读任务跳过，不做无谓编译
     */
    private boolean onlyWhenDirty = true;
}
