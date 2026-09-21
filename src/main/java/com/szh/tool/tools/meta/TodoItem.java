package com.szh.tool.tools.meta;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单个待办项：id + 内容 + 状态。
 * <p>
 * 同时作为 {@code todo_write} 工具入参、{@link TodoStore} 存储单元与
 * {@code TodoUpdatedEvent} 的落库负载，故需可被 Jackson 双向序列化（无参构造 + getter/setter）。
 *
 * @author demussong
 * @date 2026/9/22
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TodoItem {

    /**
     * 稳定标识：merge 更新时按 id 命中既有项；模型未提供时由 {@link TodoWriteTool} 兜底生成
     */
    private String id;

    /**
     * 待办内容（建议 <= 70 字）
     */
    private String content;

    /**
     * 状态，null 视为 {@link TodoStatus#PENDING}
     */
    private TodoStatus status;

    public TodoStatus safeStatus() {
        return status == null ? TodoStatus.PENDING : status;
    }
}
