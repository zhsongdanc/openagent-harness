package com.szh.memory;

/**
 * 记忆作用域：决定一条记忆在何种范围内可被召回。
 * <ul>
 *   <li>{@link #GLOBAL}：跨项目的用户级记忆（如用户偏好、沟通习惯）；</li>
 *   <li>{@link #PROJECT}：当前项目内的记忆（如项目事实、架构约定、踩坑）；</li>
 *   <li>{@link #SESSION}：仅单会话内有效的临时记忆。</li>
 * </ul>
 *
 * @author demussong
 * @date 2026/9/21
 */
public enum MemoryScope {
    GLOBAL,
    PROJECT,
    SESSION,
    ;

    /**
     * 宽松解析：大小写不敏感，无法识别时兜底为 {@link #PROJECT}（项目级是最常见的默认作用域）
     */
    public static MemoryScope from(String value) {
        if (value == null || value.isBlank()) {
            return PROJECT;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return PROJECT;
        }
    }
}
