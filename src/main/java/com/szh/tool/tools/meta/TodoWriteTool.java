package com.szh.tool.tools.meta;

import com.fasterxml.jackson.databind.JsonNode;
import com.szh.event.TodoUpdatedEvent;
import com.szh.tool.ToolContext;
import com.szh.tool.ToolDefinition;
import com.szh.utils.CommonUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 待办清单工具（对标 Claude Code TodoWrite）：让 agent 自己维护一份多步任务清单并随进度更新，
 * 是长任务里「不迷路、可追踪进度、给用户可见性」的自组织基础。
 * <p>
 * 状态双写：①进程内 {@link TodoStore}（REPL 低延迟渲染 / 跨轮复用）；②{@link TodoUpdatedEvent} 落事件日志
 * （唯一真相源，供 {@code /session} 恢复与 TraceReplay 回放）。事件是 surface 事件不进模型上下文，
 * 清单内容通过本工具回执（{@link #inlineResult()} = true）回传给模型即可。
 * <p>
 * 更新语义：默认整体替换（模型一次给出完整清单，最省心）；{@code merge=true} 时按 id 增量更新既有项、
 * 追加新项，适合只改某几条状态的场景。
 *
 * @author demussong
 * @date 2026/9/22
 */
@Slf4j
public class TodoWriteTool extends MetaToolSupport {

    public static final String CODE = "todo_write";

    private static final ToolDefinition TOOL_DEFINITION = ToolDefinition.builder()
            .name(CODE)
            .code(CODE)
            .type("system")
            .description("创建或更新本次任务的多步待办清单，用于追踪进度、向用户展示计划。"
                    + "适用场景：任务包含 3 步以上、或用户明确要求列计划时，先写清单再逐项推进，"
                    + "每完成一项就更新其状态。单次简单任务无需使用。"
                    + "默认整体替换清单；merge=true 时按 id 增量更新既有项并追加新项。"
                    + "status 取值：PENDING/IN_PROGRESS/COMPLETE/CANCELLED。同一时刻应只有一项处于 IN_PROGRESS。")
            .parameters("{\"type\":\"object\",\"properties\":{"
                    + "\"todos\":{\"type\":\"array\",\"description\":\"完整待办清单\","
                    + "\"items\":{\"type\":\"object\",\"properties\":{"
                    + "\"id\":{\"type\":\"string\",\"description\":\"稳定标识，用于 merge 更新；可省略由系统生成\"},"
                    + "\"content\":{\"type\":\"string\",\"description\":\"待办内容，简洁单行\"},"
                    + "\"status\":{\"type\":\"string\",\"enum\":[\"PENDING\",\"IN_PROGRESS\",\"COMPLETE\",\"CANCELLED\"]}"
                    + "},\"required\":[\"content\"]}},"
                    + "\"merge\":{\"type\":\"boolean\",\"description\":\"true=按 id 增量合并，false(默认)=整体替换\"}"
                    + "},\"required\":[\"todos\"]}")
            .build();

    @Override
    public String getCode() {
        return CODE;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return TOOL_DEFINITION;
    }

    @Override
    public String execute(ToolContext toolContext) {
        JsonNode args = parseArgs(toolContext.getArgs());
        JsonNode todosNode = args.get("todos");
        if (todosNode == null || !todosNode.isArray()) {
            return "缺少必填参数 todos（应为对象数组，每项含 content 与可选 status/id）。";
        }
        boolean merge = bool(args, "merge", false);
        String sessionId = toolContext.getSessionId();

        List<TodoItem> incoming = parseTodos(todosNode);
        List<TodoItem> result = merge
                ? mergeInto(TodoStore.get().list(sessionId), incoming)
                : incoming;

        TodoStore.get().update(sessionId, result);
        emit(toolContext, new TodoUpdatedEvent(sessionId, toolContext.getRunId(),
                toolContext.getTurnId(), toolContext.getRound(), result));
        log.info("todo_write: session={}, merge={}, items={}", sessionId, merge, result.size());

        String rendered = TodoStore.get().render(sessionId);
        return "待办清单已更新（" + result.size() + " 项）。\n" + rendered;
    }

    /**
     * 解析入参数组为 TodoItem 列表；缺 id 的项兜底生成，保证 merge 可稳定命中
     */
    private List<TodoItem> parseTodos(JsonNode todosNode) {
        List<TodoItem> items = new ArrayList<>();
        for (JsonNode node : todosNode) {
            if (node == null || !node.isObject()) {
                continue;
            }
            String content = text(node, "content");
            if (content == null) {
                continue;
            }
            String id = text(node, "id");
            if (id == null) {
                id = CommonUtils.generateId();
            }
            items.add(new TodoItem(id, content, TodoStatus.from(text(node, "status"))));
        }
        return items;
    }

    /**
     * 按 id 增量合并：既有项命中则更新 content/status，未命中的新项按入参顺序追加；
     * 用 LinkedHashMap 保序去重（同 id 后写覆盖前写）。
     */
    private List<TodoItem> mergeInto(List<TodoItem> existing, List<TodoItem> incoming) {
        Map<String, TodoItem> byId = new LinkedHashMap<>();
        for (TodoItem item : existing) {
            byId.put(item.getId(), item);
        }
        for (TodoItem item : incoming) {
            byId.put(item.getId(), item);
        }
        return new ArrayList<>(byId.values());
    }
}
