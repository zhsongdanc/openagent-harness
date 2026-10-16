package com.szh.tool.tools.shell;

import com.fasterxml.jackson.databind.JsonNode;
import com.szh.tool.ToolDefinition;

import java.util.ArrayList;
import java.util.List;

/**
 * @author demussong
 * @describe 按正则搜索文件内容，等价 shell 命令 grep -E [选项] 模式 [路径]
 * <p>
 * 设计决策：默认使用 ERE（扩展正则表达式，-E）而非 BRE，因为模型训练分布偏向 ERE 语法
 * （Python re / JavaScript RegExp 都是 ERE 家族），用 BRE 会导致大量“模式看起来对但匹配不
 * 到”的无效试错。需要字面匹配时模型应传 options='-F' 而非手动转义。
 * @date 2026/9/3 10:20
 */
public class GrepTool extends ShellCommandTool {

    public static final String CODE = "grep";

    private static final ToolDefinition TOOL_DEFINITION = definition(
            CODE,
            "按正则表达式搜索文件内容并返回命中行（含文件名与行号），等价 grep -E。\n"
                    + "正则方言：默认 ERE（扩展正则，等同 grep -E / Python re / JavaScript RegExp）——\n"
                    + "  • 选择: foo|bar\n"
                    + "  • 分组: (foo|bar)baz\n"
                    + "  • 量词: a+b?c{2,3}\n"
                    + "  • 字符类: [a-zA-Z_]\n"
                    + "如需字面匹配（含括号、|等元字符），传 options='-Fn'\n"
                    + "如需 BRE 兼容（如反向引用 \\1），传 options='-rn'\n"
                    + "默认递归当前工作目录，可用 path 缩小范围、options 传 -i -A 3 --include=*.java 等",
            "{\"type\":\"object\",\"properties\":{"
                    + "\"pattern\":{\"type\":\"string\",\"description\":"
                    + "\"正则表达式（ERE 扩展正则），如 'buildCommand' 或 '(get|set)[A-Z]\\\\w+' 或 '\\\\d{4}-\\\\d{2}'。"
                    + "字面匹配特殊字符请传 options='-F' 而非手动转义\"},"
                    + "\"path\":{\"type\":\"string\",\"description\":\"搜索的文件或目录，默认当前工作目录\"},"
                    + "\"options\":{\"type\":\"string\",\"description\":"
                    + "\"grep 选项，多个用空格分隔，传入后完全替换默认值 '-Ern'。"
                    + "常用: -i 忽略大小写, -l 只输出文件名, -C 3 上下文, -F 字面匹配, "
                    + "--include=*.java 筛选文件类型, -w 全词匹配。"
                    + "传自定义选项时请包含 -r -n 以保持递归和行号\"}}},"
                    + "\"required\":[\"pattern\"]}");

    @Override
    public String getCode() {
        return CODE;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return TOOL_DEFINITION;
    }

    @Override
    protected List<String> buildCommand(String args) {
        JsonNode json = parseArgs(args);
        String path = text(json, "path");

        List<String> command = new ArrayList<>();
        command.add(CODE);
        // 默认选项用 -Ern（ERE + 递归 + 行号）：兼容模型训练分布的正则语法
        String options = text(json, "options");
        if (options != null) {
            // 用户显式传了选项则完全尊重其意图，不再叠加默认
            appendArgs(command, options);
        } else {
            appendArgs(command, "-Ern");
        }
        command.add(requireText(json, "pattern"));
        command.add(path == null ? "." : path);
        return command;
    }

    /**
     * grep 退出码语义丰富：把原始 stderr 翻译为可操作提示，让模型一轮止损而不是盲目重复。
     * <ul>
     *   <li>exit 1（无匹配）：明确告知“未找到匹配”，并建议拆分模式或简化关键词</li>
     *   <li>exit 2（正则语法错误 / 文件不可读）：诊断括号不配对、非法量词等常见问题，给出修正指引</li>
     * </ul>
     */
    @Override
    protected String formatResult(int exitCode, String raw) {
        if (exitCode == 1 && raw.isBlank()) {
            // grep 无匹配：不是“无输出”，而是“没找到”——给模型明确的下一步建议
            return "grep 未找到匹配（0 命中）。建议：\n"
                    + "1. 多模式拆分：把 'A|B|C' 拆成多次单独搜索，先确认哪个关键词存在\n"
                    + "2. 简化关键词：只搜一个最独特的子串（如函数名片段）\n"
                    + "3. 确认范围：用 path 参数指定更精确的目录，或用 options='--include=*.java' 筛选文件类型\n"
                    + "4. 大小写：加 options='-i' 忽略大小写";
        }
        if (exitCode == 2) {
            // grep 语法错误（括号不配对 / 无效正则 / 路径不存在）
            String hint = diagnoseGrepError(raw);
            return raw.stripTrailing() + "\n" + hint + "\nexitCode=2";
        }
        return super.formatResult(exitCode, raw);
    }

    /**
     * 根据 grep stderr 关键词诊断常见问题并给出修正建议
     */
    private String diagnoseGrepError(String stderr) {
        if (stderr == null) {
            return "grep 执行失败（正则语法错误或路径不存在），请检查 pattern 与 path 参数";
        }
        String lower = stderr.toLowerCase();
        if (lower.contains("parentheses not balanced") || lower.contains("unbalanced parentheses")) {
            return "【正则诊断】括号不配对——请确认每个 '(' 都有对应的 ')'，"
                    + "或用 '[()]' 字符类匹配字面括号。当前已是 ERE 模式，(group) 可直接用于分组。";
        }
        if (lower.contains("invalid regular expression") || lower.contains("trailing backslash")) {
            return "【正则诊断】正则表达式无效——检查是否有悬空量词（如末尾 '*' 或 '+'）、"
                    + "未闭合的字符类 '['。若要匹配字面符号请传 options='-F'。";
        }
        if (lower.contains("no such file") || lower.contains("cannot access")) {
            return "【正则诊断】路径不存在——请检查 path 参数是否正确，或不传 path 默认搜索当前目录。";
        }
        return "grep 执行失败（exit 2），请检查正则语法与搜索路径";
    }
}
