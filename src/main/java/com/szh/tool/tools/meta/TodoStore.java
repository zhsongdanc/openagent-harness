package com.szh.tool.tools.meta;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话级待办清单存储（进程内单例，对标 Claude Code TodoWrite 的运行期待办态）。
 * <p>
 * 为什么用内存单例而不直接查事件流：REPL 每轮结束都要低延迟渲染当前清单，且 MEMORY 引擎下
 * 事件不持久，用 session 键的内存态最稳妥；同时 {@code todo_write} 仍会把清单落成
 * {@code TodoUpdatedEvent} 进事件日志（唯一真相源），{@code /session} 恢复时由 REPL 从事件流回灌本 store。
 * <p>
 * 线程安全：并行工具执行时可能多个 worker 触碰，用 {@link ConcurrentHashMap} + 整体替换保证可见性。
 *
 * @author demussong
 * @date 2026/9/22
 */
public final class TodoStore {

    private static final TodoStore INSTANCE = new TodoStore();

    public static TodoStore get() {
        return INSTANCE;
    }

    private final Map<String, List<TodoItem>> todos = new ConcurrentHashMap<>();

    private TodoStore() {
    }

    /**
     * 整体替换某 session 的待办清单（null session 直接忽略，防御测试/无会话场景）
     */
    public void update(String sessionId, List<TodoItem> items) {
        if (sessionId == null) {
            return;
        }
        todos.put(sessionId, items == null ? List.of() : List.copyOf(items));
    }

    public List<TodoItem> list(String sessionId) {
        return sessionId == null ? List.of() : todos.getOrDefault(sessionId, List.of());
    }

    public boolean has(String sessionId) {
        return !list(sessionId).isEmpty();
    }

    public void clear(String sessionId) {
        if (sessionId != null) {
            todos.remove(sessionId);
        }
    }

    /**
     * 渲染成控制台友好的清单文本；无待办时返回空串（REPL 据此决定是否打印）
     */
    public String render(String sessionId) {
        List<TodoItem> items = list(sessionId);
        if (items.isEmpty()) {
            return "";
        }
        long done = items.stream().filter(i -> i.safeStatus() == TodoStatus.COMPLETE).count();
        StringBuilder sb = new StringBuilder();
        sb.append("┌ 待办清单 (").append(done).append('/').append(items.size()).append(" 完成)\n");
        for (TodoItem item : items) {
            sb.append("│ ").append(item.safeStatus().icon()).append(' ')
                    .append(item.getContent() == null ? "" : item.getContent()).append('\n');
        }
        sb.append("└");
        return sb.toString();
    }
}
