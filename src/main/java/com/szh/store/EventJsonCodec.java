package com.szh.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.szh.context.dto.AssistantMessageItem;
import com.szh.context.dto.ReasoningMessageItem;
import com.szh.context.dto.ToolMessageItem;
import com.szh.context.dto.UserMessageItem;
import com.szh.event.CallToolFinishedEvent;
import com.szh.event.CallToolStartedEvent;
import com.szh.event.Event;
import com.szh.event.EventEnum;
import com.szh.event.ModelResponseEvent;
import com.szh.event.ReasoningEvent;
import com.szh.event.RunCompletedEvent;
import com.szh.event.RunStartedEvent;
import com.szh.event.TodoUpdatedEvent;
import com.szh.event.ModeSwitchedEvent;
import com.szh.event.UserMessageEvent;
import com.szh.tool.tools.meta.TodoItem;
import com.szh.utils.JsonUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * 事件与 {@link SerializedEvent} 之间的编解码器：把“事件如何序列化/反序列化”收敛为单一真相源，
 * 供 {@code MySqlEventStore} 与 {@code FileEventStore} 共用，保证不同存储引擎的断点恢复行为一致。
 * <p>
 * 序列化：{@code event.payloadData()} -> JSON 字符串；反序列化：按 {@code eventType} 分发，
 * 把 payload 还原成对应事件的构造参数。任何未知类型/空数据都安全兜底。
 *
 * @author demussong
 * @date 2026/9/21
 */
@Slf4j
public final class EventJsonCodec {

    private EventJsonCodec() {
    }

    /**
     * 事件 -> 可移植快照；无业务数据时 payload 为 null
     */
    public static SerializedEvent serialize(Event event) {
        String json = JsonUtil.toJson(event.payloadData());
        String payload = (json == null || json.isEmpty()) ? null : json;
        return new SerializedEvent(event.getId(), event.getTimestamp(), event.getSessionId(),
                event.getRunId(), event.getTurnId(), event.getRound(), event.getType().name(), payload);
    }

    /**
     * 可移植快照 -> 事件，按 eventType 重建；无法识别时返回 null
     */
    public static Event deserialize(SerializedEvent s) {
        if (s == null || s.eventType() == null) {
            return null;
        }
        EventEnum eventType;
        try {
            eventType = EventEnum.valueOf(s.eventType());
        } catch (IllegalArgumentException e) {
            log.warn("EventJsonCodec: unknown eventType={}, skip", s.eventType());
            return null;
        }
        String payload = s.payload();
        Event event = switch (eventType) {
            case RUN_STARTED -> new RunStartedEvent(s.sessionId(), s.runId(), s.turnId(), s.round());
            case USER_INPUT -> {
                UserMessageItem user = JsonUtil.parse(payload, UserMessageItem.class);
                yield new UserMessageEvent(s.sessionId(), s.runId(), s.turnId(), s.round(), user,
                        user == null ? null : user.getContent());
            }
            case CALL_MODEL_FINISHED -> new ModelResponseEvent(s.sessionId(), s.runId(), s.turnId(), s.round(),
                    JsonUtil.parse(payload, AssistantMessageItem.class));
            case MODEL_REASONING -> new ReasoningEvent(s.sessionId(), s.runId(), s.turnId(), s.round(),
                    JsonUtil.parse(payload, ReasoningMessageItem.class));
            case CALL_TOOL_STARTED -> {
                Map<String, String> data = parseToolStarted(payload);
                yield new CallToolStartedEvent(s.sessionId(), s.runId(), s.turnId(), s.round(),
                        data.get("toolName"), data.get("parameters"));
            }
            case CALL_TOOL_FINISHED -> {
                ToolMessageItem tool = JsonUtil.parse(payload, ToolMessageItem.class);
                yield new CallToolFinishedEvent(s.sessionId(), s.runId(), s.turnId(), s.round(), tool,
                        tool == null ? null : tool.getToolCode(),
                        tool == null ? null : tool.getExecResult());
            }
            case RUN_COMPLETED -> new RunCompletedEvent(s.sessionId(), s.runId(), s.turnId(), s.round(),
                    JsonUtil.parse(payload, String.class));
            case TODO_UPDATED -> new TodoUpdatedEvent(s.sessionId(), s.runId(), s.turnId(), s.round(),
                    parseTodos(payload));
            case MODE_SWITCHED -> new ModeSwitchedEvent(s.sessionId(), s.runId(), s.turnId(), s.round(),
                    JsonUtil.parse(payload, String.class));
        };
        event.setId(s.eventId());
        event.setTimestamp(s.timestamp());
        return event;
    }

    private static Map<String, String> parseToolStarted(String payload) {
        Map<String, String> data = JsonUtil.parse(payload, new TypeReference<Map<String, String>>() {
        });
        return data == null ? Map.of() : data;
    }

    /**
     * 待办清单负载反序列化：payload 是 {@code List<TodoItem>} 的 JSON；空/非法时回退空列表
     */
    private static List<TodoItem> parseTodos(String payload) {
        List<TodoItem> todos = JsonUtil.parse(payload, new TypeReference<List<TodoItem>>() {
        });
        return todos == null ? List.of() : todos;
    }
}
