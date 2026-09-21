package com.szh.tool.tools.meta;

import com.szh.context.dto.MessageItem;
import com.szh.context.dto.SystemMessageItem;
import com.szh.tool.Tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 规划模式（PLAN）策略：把「plan 模式该收敛哪些能力、该给模型什么额外指令」集中到一处，
 * 供两条运行时（AgentRuntime / AgentResponseRuntime）每轮调用，保证 chat 与 response 链路行为一致。
 * <p>
 * 两件事：
 * <ol>
 *   <li><b>工具收敛</b>（{@link #effectiveTools}）：PLAN 下剔除会改动仓库/外部世界的写入类工具，
 *       只留只读检索工具 + 元工具，让 agent 能看不能改，先出方案；</li>
 *   <li><b>指令注入</b>（{@link #decorateContext}）：往 system prompt 追加一段规划契约，
 *       明确「只探索、只产出方案、不动手」，与工具收敛形成软硬双约束。</li>
 * </ol>
 * 采用<b>拒绝名单</b>而非白名单：默认放行、仅剔除已知写入类工具，避免每新增一个只读工具都要改这里；
 * 写入类工具集合固定且少（文件写、git 写、构建、记忆写、子 agent 派生），漏网风险可控，
 * 且底层仍有权限闸门 + 沙箱兜底。
 *
 * @author demussong
 * @date 2026/9/22
 */
public final class PlanModePolicy {

    /**
     * PLAN 模式下禁用的写入/变更类工具 code（只读检索与元工具不在此列，照常可用）
     */
    private static final Set<String> MUTATING_CODES = Set.of(
            "write_file", "edit_file",
            "git_add", "git_commit", "git_push",
            "mvn",
            "remember", "forget",
            "dispatch_subagent");

    /**
     * PLAN 模式追加到 system prompt 的规划契约
     */
    private static final String PLANNING_DIRECTIVE = """

            # 当前处于 PLAN（规划）模式
            你现在的任务是「先想清楚、给出方案」，而不是动手改代码：
            - 只使用只读工具（read_file / repo_map / grep / find / cat / git_status / recall 等）探索代码库、收集事实；
            - 写入、提交、构建、删除类能力已被禁用，不要尝试绕过；
            - 产出一份决策完备的实现方案：要改哪些文件、每处怎么改、涉及的关键约束与取舍、验证方式；
            - 方案里若存在需要用户拍板的分叉，用 ask_user_question 提问；
            - 待用户认可后，再由用户切回 NORMAL 模式执行（你可提示用户 /mode normal）。
            用 todo_write 把方案拆成可追踪的待办清单。""";

    private PlanModePolicy() {
    }

    /**
     * 按会话当前模式返回可用工具：NORMAL 原样返回；PLAN 剔除写入类工具。
     */
    public static List<Tool> effectiveTools(List<Tool> tools, String sessionId) {
        if (tools == null || tools.isEmpty() || !AgentModeStore.get().isPlan(sessionId)) {
            return tools;
        }
        List<Tool> readOnly = new ArrayList<>();
        for (Tool tool : tools) {
            if (!MUTATING_CODES.contains(tool.getCode())) {
                readOnly.add(tool);
            }
        }
        return readOnly;
    }

    /**
     * PLAN 模式下往上下文副本的 system prompt 追加规划契约（只改副本，不动事件真相源）；
     * NORMAL 模式不做任何事。
     */
    public static void decorateContext(List<MessageItem> callContext, String sessionId) {
        if (callContext == null || !AgentModeStore.get().isPlan(sessionId)) {
            return;
        }
        for (MessageItem item : callContext) {
            if (item instanceof SystemMessageItem system) {
                String content = system.getContent() == null ? "" : system.getContent();
                if (!content.contains(PLANNING_DIRECTIVE)) {
                    system.setContent(content + PLANNING_DIRECTIVE);
                }
                return;
            }
        }
        // 理论上 system prompt 恒在首位；兜底：没有就补一条
        callContext.add(0, new SystemMessageItem(PLANNING_DIRECTIVE));
    }
}
