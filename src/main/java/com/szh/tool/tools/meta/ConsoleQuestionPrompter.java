package com.szh.tool.tools.meta;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 控制台问询器：把结构化问题与候选项打印到 stderr，从标准输入读取用户选择。
 * <p>
 * 沿用 {@code ConsolePermissionPrompter} 的约定——提示走 stderr（不污染 stdout 的最终答案输出），
 * 交互不可用（无控制台 / 输入流 EOF，如 {@code --prompt} 一次性执行或单测）时返回 {@code null}，
 * 由调用方（{@link AskUserQuestionTool}）据此告知模型「无人可答」，让其自行决策而非死等。
 * <p>
 * {@code synchronized}：并行工具执行时多个 worker 可能同时发问，加锁避免提示交错、stdin 应答串台。
 *
 * @author demussong
 * @date 2026/9/22
 */
@Slf4j
public class ConsoleQuestionPrompter {

    /**
     * 逐个问题读取用户作答。
     *
     * @return 每个问题一条答案（选项 label 或用户自定义文本）；非交互/读取失败返回 {@code null}
     */
    public synchronized List<String> ask(List<Question> questions) {
        if (questions == null || questions.isEmpty()) {
            return List.of();
        }
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            List<String> answers = new ArrayList<>();
            for (Question q : questions) {
                printQuestion(q);
                String line = reader.readLine();
                if (line == null) {
                    // EOF：非交互环境，无法作答
                    log.warn("ask_user_question got EOF (non-interactive), abort asking");
                    return null;
                }
                answers.add(interpret(q, line.trim()));
            }
            return answers;
        } catch (Exception e) {
            log.warn("read user answer failed", e);
            return null;
        }
    }

    private void printQuestion(Question q) {
        System.err.println();
        System.err.println("========== 需要你确认 ==========");
        if (q.header() != null && !q.header().isBlank()) {
            System.err.println("[" + q.header() + "]");
        }
        System.err.println(q.question());
        List<Question.Option> options = q.options();
        for (int i = 0; i < options.size(); i++) {
            Question.Option opt = options.get(i);
            String desc = (opt.description() == null || opt.description().isBlank())
                    ? "" : "  — " + opt.description();
            System.err.println("  " + (i + 1) + ") " + opt.label() + desc);
        }
        System.err.print("请输入编号" + (q.multiSelect() ? "（多选，用逗号分隔）" : "")
                + "，或直接输入自定义答案：");
        System.err.flush();
    }

    /**
     * 解析一行输入：命中候选编号则映射为对应 label（多选按逗号/空格/顿号分隔），
     * 否则把整行当作用户自定义答案；空行回退为占位说明。
     */
    private String interpret(Question q, String line) {
        if (line.isEmpty()) {
            return "(用户未作答)";
        }
        List<Question.Option> options = q.options();
        List<String> picked = new ArrayList<>();
        boolean anyIndex = false;
        for (String token : line.split("[,，、\\s]+")) {
            if (token.isEmpty()) {
                continue;
            }
            Integer idx = parseIndex(token, options.size());
            if (idx != null) {
                anyIndex = true;
                String label = options.get(idx - 1).label();
                if (!picked.contains(label)) {
                    picked.add(label);
                }
                if (!q.multiSelect()) {
                    // 单选只取第一个有效编号
                    break;
                }
            }
        }
        if (anyIndex) {
            return String.join("、", picked);
        }
        // 没有任何合法编号 => 视为用户自定义文本答案
        return line;
    }

    private Integer parseIndex(String token, int max) {
        try {
            int n = Integer.parseInt(token);
            return (n >= 1 && n <= max) ? n : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
