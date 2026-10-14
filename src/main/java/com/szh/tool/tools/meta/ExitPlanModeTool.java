package com.szh.tool.tools.meta;

import com.fasterxml.jackson.databind.JsonNode;
import com.szh.event.ModeSwitchedEvent;
import com.szh.tool.ToolContext;
import com.szh.tool.ToolDefinition;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 退出规划模式工具（对标 Claude Code 的 ExitPlanMode）：PLAN 模式下模型产出决策完备的方案后调用它，
 * 把方案连同「是否开始执行」的抉择一并抛给用户审批——批准即自动切回 NORMAL 开始改动，
 * 无需再让用户手动 {@code /mode normal}，把 PLAN 的退出从「提示用户手动切」升级成「一键批准自动切」。
 * <p>
 * 交互复用 {@link ConsoleQuestionPrompter} 读 stdin（与 {@code ask_user_question} / 权限确认同一套约定：
 * 提示走 stderr、{@code synchronized} 防并行串台、run 期间 REPL 不并发读）。仅当用户明确选择「批准」
 * 才切换模式；选择继续修改或输入自定义反馈一律留在 PLAN，把反馈回灌给模型完善方案后再次提交。
 * 非交互环境（{@code --prompt} / 单测，prompter 返回 null）不擅自切回 NORMAL（安全兜底），
 * 而是提示模型把方案作为最终答复输出供用户审阅。
 * <p>
 * 切换成功落一条 {@link ModeSwitchedEvent}（surface 事件，不进 modelContext），与 {@code switch_mode}
 * 写同一份 {@link AgentModeStore}，使 {@code /session} 恢复与 TraceReplay 回放一致。
 *
 * @author demussong
 * @date 2026/10/14
 */
@Slf4j
public class ExitPlanModeTool extends MetaToolSupport {

    public static final String CODE = "exit_plan_mode";

    /**
     * 批准选项的 label：用户选中它（prompter 回传该 label）才判定为批准，其余一律视为「继续修改/反馈」
     */
    static final String APPROVE_LABEL = "批准并开始执行";

    private static final String REVISE_LABEL = "继续修改方案";

    private static final ToolDefinition TOOL_DEFINITION = ToolDefinition.builder()
            .name(CODE)
            .code(CODE)
            .type("system")
            .description("在 PLAN（规划）模式下完成方案设计后调用：把决策完备的实现方案提交给用户审批。"
                    + "用户批准后将自动切回 NORMAL 模式，你即可开始执行改动，无需让用户手动切换模式；"
                    + "用户要求调整时会留在 PLAN 模式并回传反馈，据此完善方案后再次调用本工具。"
                    + "调用前请确保方案已足够具体（要改哪些文件、每处怎么改、关键约束与取舍、验证方式）。"
                    + "仅在处于 PLAN 模式且方案已完备时使用。")
            .parameters("{\"type\":\"object\",\"properties\":{"
                    + "\"plan\":{\"type\":\"string\",\"description\":\"完整的实现方案正文，供用户审阅后决定是否批准执行\"}"
                    + "},\"required\":[\"plan\"]}")
            .build();

    private final ConsoleQuestionPrompter prompter;

    public ExitPlanModeTool() {
        this(new ConsoleQuestionPrompter());
    }

    /**
     * 测试构造：注入自定义问询器（可模拟批准/拒绝/非交互），脱离真实 stdin 验证审批与模式切换链路
     */
    public ExitPlanModeTool(ConsoleQuestionPrompter prompter) {
        this.prompter = prompter;
    }

    @Override
    public String getCode() {
        return CODE;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return TOOL_DEFINITION;
    }

    @Override
    public String execute(ToolContext toolContext) {
        JsonNode args = parseArgs(toolContext.getArgs());
        String sessionId = toolContext.getSessionId();
        AgentModeStore store = AgentModeStore.get();

        // 不在 PLAN 模式：本工具无意义，直接告知模型可以动手，避免误切/空转
        if (!store.isPlan(sessionId)) {
            return "当前不处于 PLAN 模式（已是 NORMAL），无需退出规划；可直接开始执行改动。";
        }

        String plan = text(args, "plan");
        Question question = buildApprovalQuestion(plan);
        List<String> answers = prompter.ask(List.of(question));

        // 非交互（EOF）：无法征求批准，安全兜底——不擅自切回 NORMAL，让模型把方案作为最终答复交用户审阅
        if (answers == null || answers.isEmpty()) {
            return "方案已就绪，但当前为非交互环境无法征求用户批准，未切换模式。"
                    + "请把完整实现方案作为最终答复输出给用户审阅，待用户认可后再执行；不要擅自改动代码。";
        }

        String answer = answers.get(0) == null ? "" : answers.get(0).trim();
        if (APPROVE_LABEL.equals(answer)) {
            store.set(sessionId, AgentMode.NORMAL);
            emit(toolContext, new ModeSwitchedEvent(sessionId, toolContext.getRunId(),
                    toolContext.getTurnId(), toolContext.getRound(), AgentMode.NORMAL.name()));
            log.info("exit_plan_mode approved: session={}, PLAN -> NORMAL", sessionId);
            return "用户已批准方案，已切回 NORMAL 模式。现在可以开始按方案执行改动，"
                    + "建议用 todo_write 逐项推进并跟踪完成度。";
        }

        // 未批准：留在 PLAN，把用户反馈回灌给模型完善方案
        log.info("exit_plan_mode not approved: session={}, feedback={}", sessionId, answer);
        return "用户暂未批准方案，反馈：" + (answer.isEmpty() ? "(未说明)" : answer)
                + "。请留在 PLAN 模式据此完善方案，调整后再次调用 exit_plan_mode 提交审批。";
    }

    /**
     * 构造审批问询：把方案正文嵌进问题文本一屏展示，批准/继续修改两个互斥选项。
     * 方案缺省时给占位说明，仍允许用户结合上文对话审阅（不因缺 plan 硬失败）。
     */
    private Question buildApprovalQuestion(String plan) {
        String body = (plan == null || plan.isBlank())
                ? "(模型未提供方案正文，请结合上文对话审阅)"
                : plan.strip();
        String questionText = "规划已完成，请审阅以下实现方案并决定是否开始执行：\n\n"
                + body
                + "\n\n批准后 agent 将切回 NORMAL 模式并按方案开始改动代码；如需调整请选择「"
                + REVISE_LABEL + "」并补充意见。";
        return new Question(questionText, "方案审批", false, List.of(
                new Question.Option(APPROVE_LABEL, "认可方案，切回 NORMAL 开始改动"),
                new Question.Option(REVISE_LABEL, "保持 PLAN，我要补充调整意见")));
    }
}
