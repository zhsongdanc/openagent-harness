package com.szh.memory;

/**
 * 记忆整理动作：候选记忆与正式记忆库中既有条目的关系判定结果，取代第一阶段
 * 「相似即覆盖」的单值 merge 语义（见第二阶段方案）。
 * <p>各动作的判据与后果：
 * <ul>
 *   <li>{@link #ADD}：没有可靠的既有相似记忆，作为新记忆插入；</li>
 *   <li>{@link #UPDATE}：同一事实的修正，保留旧记忆 id 与 createdAt，正文以新值为准（旧值归档整理日志）；</li>
 *   <li>{@link #MERGE}：两条互补（如旧偏好被新表述细化），由 LLM 生成合并后文本写回旧 id；</li>
 *   <li>{@link #SUPERSEDE}：新事实取代旧事实（如数据库迁移），旧行 JSON 归档进整理日志后删除，插入新行；</li>
 *   <li>{@link #SKIP}：与既有记忆完全重复（规范化正文相等），不写库，只把候选指向既有记忆 id（幂等来源）；</li>
 *   <li>{@link #DISCARD}：低可信度的自动候选或仲裁判定无保存价值，丢弃并记日志。</li>
 * </ul>
 *
 * @author demussong
 * @date 2026-10-16
 */
public enum ConsolidationAction {
    ADD,
    UPDATE,
    MERGE,
    SUPERSEDE,
    SKIP,
    DISCARD,
    ;

    /**
     * 宽松解析 LLM 仲裁输出：大小写/时态不敏感（如 "replaced"→SUPERSEDE），无法识别返回 null
     * 交由调用方回退启发式判定——仲裁模型的用词不可控，宁可回退不可猜错动作。
     */
    public static ConsolidationAction from(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim().toLowerCase();
        if (v.startsWith("update") || v.startsWith("correct") || v.startsWith("revis")) {
            return UPDATE;
        }
        if (v.startsWith("merge") || v.startsWith("combin")) {
            return MERGE;
        }
        if (v.startsWith("super") || v.startsWith("replac") || v.startsWith("overwrit")) {
            return SUPERSEDE;
        }
        if (v.startsWith("add") || v.startsWith("insert") || v.startsWith("new") || v.startsWith("coexist")) {
            return ADD;
        }
        if (v.startsWith("skip") || v.startsWith("duplicat")) {
            return SKIP;
        }
        if (v.startsWith("discard") || v.startsWith("drop") || v.startsWith("delete") || v.startsWith("ignore")) {
            return DISCARD;
        }
        return null;
    }
}
