package com.szh.agent.verify;

import com.szh.context.dto.MessageItem;
import com.szh.context.dto.SystemMessageItem;

import java.util.List;

/**
 * 验证闭环的软性提示策略：验证闸门武装时，往 system prompt 副本追加一句契约，鼓励模型改完代码后
 * 主动运行编译/测试再宣布完成，提高一次通过率（硬闸门之外的补充，二者共用同一份 {@link VerifyConfig}）。
 * <p>
 * 与 {@code PlanModePolicy.decorateContext} 同一范式：只改上下文副本、不动事件真相源；两条运行时每轮各调一次，
 * 保证 chat 与 response 链路行为一致。
 *
 * @author demussong
 * @date 2026/10/9
 */
public final class VerifyPolicy {

    /**
     * 验证启用时追加到 system prompt 的契约
     */
    private static final String VERIFY_DIRECTIVE = """

            # 完成前的自我验证
            本项目已开启验证闭环：当你改动代码（write_file/edit_file/git 提交）后准备宣布任务完成时，
            框架会自动运行项目配置的编译/测试命令；未通过则会把失败输出回灌给你、要求你继续修复。
            为减少来回，请在收尾前主动运行编译/测试自查（如 mvn -q -DskipTests compile、mvn -q test 或项目约定命令），
            确认无误再给出最终答复。""";

    private VerifyPolicy() {
    }

    /**
     * 验证启用时往上下文副本的 system prompt 追加自我验证契约（只改副本）；未启用不做任何事。
     *
     * @param callContext   本轮要发给模型的上下文副本
     * @param verifyEnabled 验证闸门是否武装
     */
    public static void decorateContext(List<MessageItem> callContext, boolean verifyEnabled) {
        if (callContext == null || !verifyEnabled) {
            return;
        }
        for (MessageItem item : callContext) {
            if (item instanceof SystemMessageItem system) {
                String content = system.getContent() == null ? "" : system.getContent();
                if (!content.contains(VERIFY_DIRECTIVE)) {
                    system.setContent(content + VERIFY_DIRECTIVE);
                }
                return;
            }
        }
        // 兜底：没有 system prompt 就补一条
        callContext.add(0, new SystemMessageItem(VERIFY_DIRECTIVE));
    }
}
