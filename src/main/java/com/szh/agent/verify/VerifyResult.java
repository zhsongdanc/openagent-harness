package com.szh.agent.verify;

/**
 * 验证结果：承载「通过与否 + 失败命令 + 退出码 + 输出 + 附注」，并负责把失败信息渲染成
 * 回灌给模型的修复指令文本（{@link #feedback}）与给用户看的简报（{@link #summary}）。
 * <p>
 * 用 record 承载，可直接被 Jackson 序列化进事件日志（配合 {@code VerificationEvent}）。
 *
 * @param passed        是否全部验证命令通过
 * @param failedCommand 首个失败的命令；通过时为 null
 * @param exitCode      失败命令的退出码；被拦截/异常/超时为 -1
 * @param output        失败命令的输出（已做尾部截断，报错通常在末尾）
 * @param note          失败附注（被安全策略拦截 / 执行异常 / 超时等原因），无则为 null
 * @author demussong
 * @date 2026/10/9
 */
public record VerifyResult(boolean passed, String failedCommand, int exitCode, String output, String note) {

    /**
     * 全部通过
     */
    public static VerifyResult pass() {
        return new VerifyResult(true, null, 0, "", null);
    }

    /**
     * 命令退出码非 0：最常见的编译/测试失败
     */
    public static VerifyResult fail(String command, int exitCode, String output) {
        return new VerifyResult(false, command, exitCode, output == null ? "" : output, null);
    }

    /**
     * 命令未能正常执行（被安全策略拦截 / 执行异常 / 超时），原因写入 note
     */
    public static VerifyResult blocked(String command, String note) {
        return new VerifyResult(false, command, -1, "", note);
    }

    /**
     * 回灌给模型的修复指令：把失败命令、退出码、输出与「未通过前别宣布完成」的约束拼成一条 user 消息。
     *
     * @param round 当前是第几次验证-修复（从 1 开始）
     * @param max   最大修复轮次
     */
    public String feedback(int round, int max) {
        StringBuilder sb = new StringBuilder();
        sb.append("[自动验证未通过 · 第 ").append(round).append('/').append(max).append(" 次]\n")
                .append("你在改动代码后宣布完成，但项目验证命令未通过。请定位并修复以下错误，修复后重新完成；")
                .append("验证通过前不要再次宣布完成。\n")
                .append("失败命令: ").append(failedCommand).append('\n');
        if (note != null && !note.isBlank()) {
            sb.append("原因: ").append(note).append('\n');
        } else {
            sb.append("退出码: ").append(exitCode).append('\n');
        }
        if (output != null && !output.isBlank()) {
            sb.append("输出(尾部):\n").append(output);
        }
        return sb.toString();
    }

    /**
     * 给用户看的一行简报（超限收尾时使用）
     */
    public String summary() {
        if (passed) {
            return "验证通过";
        }
        String reason = (note != null && !note.isBlank()) ? note : ("退出码 " + exitCode);
        return "命令 [" + failedCommand + "] 未通过：" + reason;
    }
}
