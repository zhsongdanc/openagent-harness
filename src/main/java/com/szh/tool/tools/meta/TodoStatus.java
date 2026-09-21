package com.szh.tool.tools.meta;

/**
 * 待办项状态（对标 Claude Code TodoWrite 的四态）。
 * <p>
 * {@link #from(String)} 对模型回传做宽松归一化（大小写、常见别名），避免因写法差异丢状态；
 * 归一化后落到 {@link TodoItem}，序列化进事件时统一用枚举名，反序列化天然对齐。
 *
 * @author demussong
 * @date 2026/9/22
 */
public enum TodoStatus {

    /**
     * 待处理
     */
    PENDING,

    /**
     * 进行中
     */
    IN_PROGRESS,

    /**
     * 已完成
     */
    COMPLETE,

    /**
     * 已取消 / 不再相关
     */
    CANCELLED;

    /**
     * 宽松解析：null/未知一律回退 {@link #PENDING}，兼容 in_progress/inprogress/doing、
     * complete/completed/done、cancelled/canceled 等常见别名。
     */
    public static TodoStatus from(String raw) {
        if (raw == null) {
            return PENDING;
        }
        String v = raw.trim().toLowerCase().replace("-", "_").replace(" ", "_");
        return switch (v) {
            case "in_progress", "inprogress", "doing", "active", "working" -> IN_PROGRESS;
            case "complete", "completed", "done", "finished" -> COMPLETE;
            case "cancelled", "canceled", "cancel", "dropped" -> CANCELLED;
            default -> PENDING;
        };
    }

    /**
     * 控制台渲染用的状态图标
     */
    public String icon() {
        return switch (this) {
            case COMPLETE -> "[x]";
            case IN_PROGRESS -> "[>]";
            case CANCELLED -> "[-]";
            case PENDING -> "[ ]";
        };
    }
}
