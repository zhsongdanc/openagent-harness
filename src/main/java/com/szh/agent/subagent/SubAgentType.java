package com.szh.agent.subagent;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 子 agent 类型：对标 Claude Code 的 Task 工具（general-purpose / code-review 等），
 * 每种类型绑定一份「角色指令 + 工具白名单」，决定派生出来的子 agent 能干什么、以什么身份干。
 * <p>
 * 设计要点：
 * <ol>
 *   <li>工具白名单是子 agent 与主 agent 的核心区别——只读型子 agent（code-review/explore）
 *       拿不到 write_file/edit_file/git_commit 等写工具，从结构上杜绝越权改动；</li>
 *   <li>{@code allowedCodes == null} 表示放开全部工具（general-purpose），
 *       但 dispatch_subagent 是否可得另由 {@code subagent.maxDepth} 深度闸门决定（见 ToolRegistry）；</li>
 *   <li>角色指令追加在分层 system prompt 之后，明确「你是子 agent、独立上下文、只回最终结论」的契约，
 *       避免子 agent 反问用户或把中间产物当结论回传。</li>
 * </ol>
 *
 * @author demussong
 * @date 2026/9/22
 */
public enum SubAgentType {

    /**
     * 通用子 agent：拥有与主 agent 同级的全部工具，适合"把一段独立的探索/实现子任务整体外包出去"，
     * 中间产物（大量文件读取、搜索、试错）关在子上下文里烧，主 agent 只收最终结论。
     */
    GENERAL_PURPOSE("general-purpose",
            "通用子 agent，拥有全部工具，适合外包一段可独立完成、会产生大量中间产物的子任务（探索、实现、调研）。",
            null,
            "你现在是一个被主 agent 派生的【通用子 agent】，运行在独立的上下文窗口中：\n"
                    + "- 你看不到主 agent 的对话历史，只能依据下面这条任务指令独立工作；\n"
                    + "- 你无法向用户提问，遇到歧义时按最合理的假设推进并说明；\n"
                    + "- 你可以自由调用工具（读写文件、执行命令、搜索等）完成任务；\n"
                    + "- 完成后，把【最终结论】作为你的最后一条消息返回：主 agent 只会看到这条结论，"
                    + "看不到你的中间步骤，所以结论要自包含、可直接采用（包含关键文件路径、改动摘要或答案本身），"
                    + "不要只说“已完成”而不给结果。"),

    /**
     * 代码审查子 agent：只读工具子集，专注审阅代码/变更并给出问题清单，绝不改动仓库。
     */
    CODE_REVIEW("code-review",
            "代码审查子 agent，只读工具（读文件/搜索/repo_map/git 只读查询），审阅代码或改动并给出问题清单，不会修改仓库。",
            Set.of("read_file", "repo_map", "read_tool_result",
                    "grep", "find", "cat", "head", "tail", "ls", "pwd", "git", "git_status"),
            "你现在是一个被主 agent 派生的【代码审查子 agent】，运行在独立的上下文窗口中：\n"
                    + "- 你只有只读工具，无法修改任何文件，请专注于审阅与分析；\n"
                    + "- 你无法向用户提问，按任务指令独立审查；\n"
                    + "- 重点关注：逻辑正确性、边界与异常处理、并发/线程安全、与项目既有约定的一致性、潜在安全隐患；\n"
                    + "- 完成后，把审查结论作为最后一条消息返回：按「阻断性问题 / 建议改进 / 可选优化」分级列出，"
                    + "每条给出具体文件与行号定位和修改建议。主 agent 只会看到这条结论。"),

    /**
     * 代码库探索子 agent：只读检索工具子集，用于"这个功能在哪实现/这块架构怎么走"这类需要大范围翻代码、
     * 会产生大量中间读取产物的定位任务，只回精炼结论。
     */
    EXPLORE("explore",
            "代码库探索子 agent，只读检索工具（搜索/读文件/repo_map），用于定位实现、梳理调用链等需大范围翻代码的任务，只回精炼结论。",
            Set.of("read_file", "repo_map", "read_tool_result",
                    "grep", "find", "cat", "head", "tail", "ls", "pwd"),
            "你现在是一个被主 agent 派生的【代码库探索子 agent】，运行在独立的上下文窗口中：\n"
                    + "- 你只有只读检索工具，任务是高效定位代码、梳理实现与调用关系；\n"
                    + "- 你无法向用户提问，按任务指令独立探索；\n"
                    + "- 完成后，把探索结论作为最后一条消息返回：直接给出关键文件路径、核心符号、调用链或答案，"
                    + "并附最必要的代码位置引用，不要把你翻过的所有文件都罗列出来。主 agent 只会看到这条结论。");

    private final String code;
    private final String description;
    /**
     * 工具白名单：null 表示放开全部工具，否则子 agent 只能拿到集合内的工具 code
     */
    private final Set<String> allowedCodes;
    private final String rolePrompt;

    SubAgentType(String code, String description, Set<String> allowedCodes, String rolePrompt) {
        this.code = code;
        this.description = description;
        this.allowedCodes = allowedCodes;
        this.rolePrompt = rolePrompt;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public Set<String> allowedCodes() {
        return allowedCodes;
    }

    public String rolePrompt() {
        return rolePrompt;
    }

    /**
     * 宽松解析类型 code：不传/未知一律回退 general-purpose，避免模型拼错类型名就直接失败。
     */
    public static SubAgentType from(String code) {
        if (code == null || code.isBlank()) {
            return GENERAL_PURPOSE;
        }
        String normalized = code.trim().toLowerCase();
        return Stream.of(values())
                .filter(t -> t.code.equals(normalized))
                .findFirst()
                .orElse(GENERAL_PURPOSE);
    }

    /**
     * 生成可用类型清单，嵌进 dispatch_subagent 的工具描述，让模型显式感知有哪些子 agent 可派生。
     */
    public static String catalog() {
        return Stream.of(values())
                .map(t -> t.code + "（" + t.description + "）")
                .collect(Collectors.joining("；"));
    }
}
