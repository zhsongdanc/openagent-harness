package com.szh.memory;

/**
 * 活动目标状态（第三阶段）：个人助手不仅记住过去，还应知道「哪些事尚未完成」。
 * 一条目标从登记到终结的有限状态机，取代把 TODO 混进普通 {@link MemoryCategory#OTHER} 记忆。
 * <ul>
 *   <li>{@link #ACTIVE}：进行中，会被常驻注入到 prompt；</li>
 *   <li>{@link #DONE}：已完成（终态）；</li>
 *   <li>{@link #CANCELLED}：已取消（终态）；</li>
 *   <li>{@link #SUPERSEDED}：被新目标取代（终态，保留可追溯而非物理删除）。</li>
 * </ul>
 *
 * @author demussong
 * @date 2026-10-16
 */
public enum GoalStatus {
    ACTIVE,
    DONE,
    CANCELLED,
    SUPERSEDED,
    ;

    /** 是否为终态（不再作为「进行中」注入 prompt） */
    public boolean isTerminal() {
        return this != ACTIVE;
    }

    /**
     * 宽松解析：大小写/常见别名，未知回退 {@link #ACTIVE}（新登记目标默认进行中，绝不因写错状态被误判完成）。
     */
    public static GoalStatus from(String value) {
        if (value == null || value.isBlank()) {
            return ACTIVE;
        }
        String v = value.trim().toUpperCase().replace('-', '_').replace(' ', '_');
        switch (v) {
            case "DONE":
            case "COMPLETE":
            case "COMPLETED":
            case "FINISHED":
                return DONE;
            case "CANCEL":
            case "CANCELED":
            case "CANCELLED":
            case "ABANDON":
            case "ABANDONED":
            case "DROP":
            case "DROPPED":
                return CANCELLED;
            case "SUPERSEDE":
            case "SUPERSEDED":
            case "REPLACED":
                return SUPERSEDED;
            case "ACTIVE":
            case "ONGOING":
            case "PENDING":
            case "OPEN":
                return ACTIVE;
            default:
                return ACTIVE;
        }
    }
}
