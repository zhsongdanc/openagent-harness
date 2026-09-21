package com.szh.event;

import com.szh.tool.tools.meta.TodoItem;

import java.util.List;

/**
 * 待办清单更新事件：{@code todo_write} 元工具每次改写清单时落一条，作为「agent 自组织进度」的真相源。
 * <p>
 * 刻意<b>不</b>继承 {@link MessageEvent}——它是面向用户/REPL 的 surface 事件，不该进入 modelContext
 * （清单内容已经通过 todo_write 的工具结果回执给了模型，再塞进上下文既冗余又会破坏 Responses API 分组）。
 * 因此 {@code AgentState.resume()} 重建上下文时天然忽略它，只由 REPL 渲染 / TraceReplay 回放消费。
 *
 * @author demussong
 * @date 2026/9/22
 */
public class TodoUpdatedEvent extends Event {

    private final List<TodoItem> todos;

    public TodoUpdatedEvent(String sessionId, String runId, String turnId, int round, List<TodoItem> todos) {
        super();
        this.sessionId = sessionId;
        this.runId = runId;
        this.turnId = turnId;
        this.round = round;
        this.todos = todos == null ? List.of() : List.copyOf(todos);
    }

    public List<TodoItem> getTodos() {
        return todos;
    }

    @Override
    public EventEnum getType() {
        return EventEnum.TODO_UPDATED;
    }

    @Override
    public Object payloadData() {
        return todos;
    }
}
