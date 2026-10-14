package com.szh.agent.verify;

import lombok.extern.slf4j.Slf4j;

import java.util.Set;

/**
 * 验证闸门：run 级实例，编排「本 run 是否改过代码 → 模型宣布完成时是否强制验证 → 失败后最多重修几轮」。
 * <p>
 * 对标 Aider {@code --auto-test} 与 Claude Code {@code Stop} hook：拦截「模型宣布完成」这一刻，
 * 用客观的编译/测试结果决定是否放行收尾，把「靠 prompt 求模型记得跑测试」升级为框架级硬闸门。
 * <p>
 * 两条运行时（{@code AgentRuntime} / {@code AgentResponseRuntime}）各在 run 开始建一个实例，
 * 每次执行工具调用后 {@link #markDirty(String)} 标记是否改动过代码，收尾分支调 {@link #shouldVerify()}。
 *
 * @author demussong
 * @date 2026/10/9
 */
@Slf4j
public class VerifyGate {

    /**
     * 判定「本 run 改过代码」的写入类工具 code：命中任一即置 dirty。
     * 只收文件写与 git 写（真正改动仓库内容的），mvn/remember 等不计入——它们不产生需要重新验证的代码变更。
     */
    private static final Set<String> DIRTY_CODES = Set.of(
            "write_file", "edit_file", "delete_file", "git_add", "git_commit", "git_push");

    private final VerifyConfig config;

    private final String workspace;

    /**
     * 本 run 是否改动过代码（onlyWhenDirty 时决定要不要验证）
     */
    private boolean mutated;

    /**
     * 已进行的验证-修复轮次
     */
    private int verifyRounds;

    public VerifyGate(String workspace) {
        this.workspace = workspace;
        this.config = VerifyConfigLoader.load(workspace);
    }

    /**
     * 测试构造：直接注入配置，脱离配置文件验证闸门逻辑
     */
    public VerifyGate(String workspace, VerifyConfig config) {
        this.workspace = workspace;
        this.config = config == null ? new VerifyConfig() : config;
    }

    /**
     * 标记一次工具调用：命中写入类工具即认为本 run 改过代码
     */
    public void markDirty(String toolCode) {
        if (toolCode != null && DIRTY_CODES.contains(toolCode)) {
            mutated = true;
        }
    }

    /**
     * 闸门是否武装：配置开启且确有验证命令可跑
     */
    public boolean isArmed() {
        return config.isEnabled() && config.getCommands() != null && !config.getCommands().isEmpty();
    }

    /**
     * 本次收尾是否要触发验证：闸门武装，且（未开启 onlyWhenDirty 或本 run 确实改过代码）
     */
    public boolean shouldVerify() {
        return isArmed() && (!config.isOnlyWhenDirty() || mutated);
    }

    /**
     * 跑一遍全部验证命令
     */
    public VerifyResult runVerification() {
        return Verifier.verify(config, workspace);
    }

    /**
     * 是否还能再逼模型修一轮
     */
    public boolean canRetry() {
        return verifyRounds < config.getMaxFixRounds();
    }

    public void incrementRound() {
        verifyRounds++;
    }

    public int round() {
        return verifyRounds;
    }

    public int maxFixRounds() {
        return config.getMaxFixRounds();
    }

    public VerifyConfig config() {
        return config;
    }
}
