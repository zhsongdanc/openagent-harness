package com.szh.tool.tools.meta;

import com.fasterxml.jackson.databind.JsonNode;
import com.szh.tool.ToolContext;
import com.szh.tool.ToolDefinition;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 结构化问询工具（对标 Claude Code AskUserQuestion）：让 agent 在需要用户拍板时，抛出带候选项的
 * 多选/单选问题，而不是用自然语言开放式提问——用户点选即可，答案结构化回传给模型继续推进。
 * <p>
 * 交互实现复用 {@link ConsoleQuestionPrompter} 读 stdin（与权限确认同一套约定：提示走 stderr、
 * run 期间 REPL 不并发读、非交互 EOF 时不死等）。非交互场景（{@code --prompt} 一次性执行 / 单测）
 * 返回「无人可答」提示，引导模型基于既有信息自行决策或提示用户补充。
 * <p>
 * 不落事件：问询是即时交互，答案通过工具结果（{@link #inlineResult()} = true）直接回传模型即可，
 * 无需进事件日志真相源。
 *
 * @author demussong
 * @date 2026/9/22
 */
@Slf4j
public class AskUserQuestionTool extends MetaToolSupport {

    public static final String CODE = "ask_user_question";

    private static final ToolDefinition TOOL_DEFINITION = ToolDefinition.builder()
            .name(CODE)
            .code(CODE)
            .type("system")
            .description("在需要用户做选择/拍板时，向用户抛出一个或多个带候选项的结构化问题（单选或多选），"
                    + "用户点选后答案会结构化返回。适用场景：存在多个合理方案需用户偏好、需求有歧义需澄清、"
                    + "要在几个方向里做取舍时。不要用它在已能自行合理决策时打断用户。"
                    + "每个问题给 2~4 个互斥候选项，用户也可自行输入自定义答案。")
            .parameters("{\"type\":\"object\",\"properties\":{"
                    + "\"questions\":{\"type\":\"array\",\"description\":\"1~4 个问题\","
                    + "\"items\":{\"type\":\"object\",\"properties\":{"
                    + "\"question\":{\"type\":\"string\",\"description\":\"完整问题文本\"},"
                    + "\"header\":{\"type\":\"string\",\"description\":\"<=12 字的极简标签，可省略\"},"
                    + "\"multiSelect\":{\"type\":\"boolean\",\"description\":\"是否多选，默认 false\"},"
                    + "\"options\":{\"type\":\"array\",\"description\":\"2~4 个候选项\","
                    + "\"items\":{\"type\":\"object\",\"properties\":{"
                    + "\"label\":{\"type\":\"string\",\"description\":\"选项展示文本，1~5 字\"},"
                    + "\"description\":{\"type\":\"string\",\"description\":\"选项含义/取舍说明\"}"
                    + "},\"required\":[\"label\"]}}}"
                    + "},\"required\":[\"question\",\"options\"]}}"
                    + "},\"required\":[\"questions\"]}")
            .build();

    private final ConsoleQuestionPrompter prompter;

    public AskUserQuestionTool() {
        this(new ConsoleQuestionPrompter());
    }

    /**
     * 测试构造：注入自定义问询器（可模拟用户应答），脱离真实 stdin 验证解析与回执链路
     */
    public AskUserQuestionTool(ConsoleQuestionPrompter prompter) {
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
        List<Question> questions = parseQuestions(args);
        if (questions.isEmpty()) {
            return "缺少有效的问题：questions 应为非空数组，每题含 question 与至少 2 个 options（label 必填）。";
        }
        List<String> answers = prompter.ask(questions);
        if (answers == null) {
            return "当前为非交互环境，无法向用户提问。请基于已有信息自行做出合理决策并继续，"
                    + "或在最终答复里说明需要用户补充哪些信息。";
        }
        StringBuilder sb = new StringBuilder("用户回复：\n");
        for (int i = 0; i < questions.size(); i++) {
            String answer = i < answers.size() ? answers.get(i) : "";
            sb.append("Q").append(i + 1).append(": ").append(questions.get(i).question()).append('\n');
            sb.append("A").append(i + 1).append(": ").append(answer).append('\n');
        }
        log.info("ask_user_question answered: {} question(s)", questions.size());
        return sb.toString().trim();
    }

    /**
     * 解析问题数组；兼容顶层直接给单个问题（question+options）的扁平写法
     */
    private List<Question> parseQuestions(JsonNode args) {
        List<Question> questions = new ArrayList<>();
        JsonNode questionsNode = args.get("questions");
        if (questionsNode != null && questionsNode.isArray()) {
            for (JsonNode node : questionsNode) {
                Question q = parseQuestion(node);
                if (q != null) {
                    questions.add(q);
                }
            }
            return questions;
        }
        Question single = parseQuestion(args);
        if (single != null) {
            questions.add(single);
        }
        return questions;
    }

    private Question parseQuestion(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String question = text(node, "question");
        List<Question.Option> options = parseOptions(node.get("options"));
        // 问题文本与至少 2 个候选项是可用问询的底线，否则丢弃该问题
        if (question == null || options.size() < 2) {
            return null;
        }
        return new Question(question, text(node, "header"), bool(node, "multiSelect", false), options);
    }

    /**
     * 候选项兼容两种写法：对象 {label, description} 或纯字符串
     */
    private List<Question.Option> parseOptions(JsonNode optionsNode) {
        List<Question.Option> options = new ArrayList<>();
        if (optionsNode == null || !optionsNode.isArray()) {
            return options;
        }
        for (JsonNode node : optionsNode) {
            if (node == null || node.isNull()) {
                continue;
            }
            if (node.isObject()) {
                String label = text(node, "label");
                if (label != null) {
                    options.add(new Question.Option(label, text(node, "description")));
                }
            } else {
                String label = node.asText().trim();
                if (!label.isEmpty()) {
                    options.add(new Question.Option(label, null));
                }
            }
        }
        return options;
    }
}
