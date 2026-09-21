package com.szh.memory;

/**
 * 记忆分类：对齐主流 harness 的记忆类型划分，便于按类过滤与后续生命周期管理。
 *
 * @author demussong
 * @date 2026/9/21
 */
public enum MemoryCategory {
    /** 用户偏好：沟通风格、行为习惯、个人设定 */
    USER_PREFERENCE,
    /** 项目事实：技术栈、架构、配置、目录约定 */
    PROJECT_INFO,
    /** 开发规范：编码/测试/注释等团队约定 */
    DEVELOPMENT_SPEC,
    /** 经验教训：可复用的做法、任务经验 */
    EXPERIENCE,
    /** 踩坑记录：Bug 现象-根因-修复-教训 */
    PITFALL,
    /** 工程决策：结论、权衡、被否决的备选方案 */
    DECISION,
    /** 其它未归类 */
    OTHER,
    ;

    /**
     * 宽松解析：大小写不敏感，兼容常见别名，无法识别时兜底为 {@link #OTHER}
     */
    public static MemoryCategory from(String value) {
        if (value == null || value.isBlank()) {
            return OTHER;
        }
        String v = value.trim().toUpperCase().replace('-', '_').replace(' ', '_');
        try {
            return valueOf(v);
        } catch (IllegalArgumentException e) {
            // 兼容 LLM 可能返回的近义写法
            if (v.contains("PREFER") || v.contains("USER")) {
                return USER_PREFERENCE;
            }
            if (v.contains("PROJECT") || v.contains("ARCH") || v.contains("FACT")) {
                return PROJECT_INFO;
            }
            if (v.contains("SPEC") || v.contains("CONVENTION") || v.contains("RULE")) {
                return DEVELOPMENT_SPEC;
            }
            if (v.contains("PITFALL") || v.contains("BUG")) {
                return PITFALL;
            }
            if (v.contains("DECISION")) {
                return DECISION;
            }
            if (v.contains("EXPERIENCE") || v.contains("LESSON")) {
                return EXPERIENCE;
            }
            return OTHER;
        }
    }
}
