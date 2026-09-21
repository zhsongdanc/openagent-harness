package com.szh.tool.tools.meta;

import java.util.List;

/**
 * 结构化问询的单个问题（对标 Claude Code AskUserQuestion）。
 *
 * @param question    完整问题文本
 * @param header      极简标签（用于分组展示，可空）
 * @param multiSelect 是否允许多选
 * @param options     候选项，至少 2 个
 * @author demussong
 * @date 2026/9/22
 */
public record Question(String question, String header, boolean multiSelect, List<Option> options) {

    /**
     * 单个候选项：展示标签 + 说明（说明可空）
     */
    public record Option(String label, String description) {
    }
}
