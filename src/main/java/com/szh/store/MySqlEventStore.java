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
import com.szh.event.UserMessageEvent;
import com.szh.store.db.AgentEventPO;
import com.szh.store.db.AgentEventRepository;
import com.szh.utils.JsonUtil;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @author demussong
 * @describe
 * @date 2026/9/1 18:26
 */
public class MySqlEventStore implements EventStore {

    /**
     * eventId 存储前缀，{@link #buildEventId} 与 {@link #stripEventId} 共用，保证读写一致
     */
    private static final String EVENT_ID_PREFIX = "event_";

    /**
     * 无业务数据事件的 payload 占位值：payload 列是 NOT NULL，必须写入合法 JSON 而不是 null
     */
    private static final String EMPTY_PAYLOAD = "{}";

    /**
     * 事件结构版本，落库时统一给默认值，避免字段为 null 被 insert 语句跳过
     */
    private static final int DEFAULT_EVENT_VERSION = 1;

    @Override
    public void appendEvent(Event event) {
        AgentEventRepository agentEventRepository = new AgentEventRepository();
        agentEventRepository.insert(convertToAgentEventPO(event));
    }

    @Override
    public StoreEnum getStoreType() {
        return StoreEnum.MYSQL;
    }

    @Override
    public List<Event> getEvents(String sessionId) {
        AgentEventRepository agentEventRepository = new AgentEventRepository();
        List<AgentEventPO> agentEventPOS = agentEventRepository.findBySessionId(sessionId);

        return agentEventPOS.stream()
                .map(this::convertToEvent)
                .collect(Collectors.toList());
    }

    private AgentEventPO convertToAgentEventPO(Event event) {
        AgentEventPO agentEventPO = new AgentEventPO();
        agentEventPO.setEventId(buildEventId(event.getId()));
        agentEventPO.setSessionId(event.getSessionId());
        agentEventPO.setRunId(event.getRunId());
        agentEventPO.setTurnId(event.getTurnId());
        agentEventPO.setEventType(event.getType().name());
        agentEventPO.setEventVersion(DEFAULT_EVENT_VERSION);
        // 每个事件自带完整数据，统一序列化进 payload（不再只认 MessageEvent）
        agentEventPO.setPayload(writePayload(event));
        agentEventPO.setEventTime(event.getTimestamp());
        agentEventPO.setCtime(java.time.LocalDateTime.now());

        return agentEventPO;
    }

    private Event convertToEvent(AgentEventPO agentEventPO) {
        EventEnum eventType = EventEnum.valueOf(agentEventPO.getEventType());
        String sessionId = agentEventPO.getSessionId();
        String runId = agentEventPO.getRunId();
        String turnId = agentEventPO.getTurnId();
        String payload = readPayload(agentEventPO);

        // 按 eventType 把 payload 反序列化回对应数据，重建完整事件
        Event event = switch (eventType) {
            case RUN_STARTED -> new RunStartedEvent(sessionId, runId, turnId);
            case USER_INPUT -> {
                UserMessageItem user = JsonUtil.parse(payload, UserMessageItem.class);
                yield new UserMessageEvent(sessionId, runId, turnId, user,
                        user == null ? null : user.getContent());
            }
            case CALL_MODEL_FINISHED -> new ModelResponseEvent(sessionId, runId, turnId,
                    JsonUtil.parse(payload, AssistantMessageItem.class));
            case MODEL_REASONING -> new ReasoningEvent(sessionId, runId, turnId,
                    JsonUtil.parse(payload, ReasoningMessageItem.class));
            case CALL_TOOL_STARTED -> {
                Map<String, String> data = parseToolStarted(payload);
                yield new CallToolStartedEvent(sessionId, runId, turnId,
                        data.get("toolName"), data.get("parameters"));
            }
            case CALL_TOOL_FINISHED -> {
                ToolMessageItem tool = JsonUtil.parse(payload, ToolMessageItem.class);
                yield new CallToolFinishedEvent(sessionId, runId, turnId, tool,
                        tool == null ? null : tool.getToolCode(),
                        tool == null ? null : tool.getExecResult());
            }
            case RUN_COMPLETED -> new RunCompletedEvent(sessionId, runId, turnId,
                    JsonUtil.parse(payload, String.class));
        };
        event.setId(stripEventId(agentEventPO.getEventId()));
        event.setTimestamp(agentEventPO.getEventTime());

        return event;
    }

    private Map<String, String> parseToolStarted(String payload) {
        Map<String, String> data = JsonUtil.parse(payload, new TypeReference<Map<String, String>>() {
        });
        return data == null ? Map.of() : data;
    }

    /**
     * 写入侧兜底：payloadData 为空或序列化失败时写占位 JSON，保证 NOT NULL 列始终有值
     */
    private String writePayload(Event event) {
        String json = JsonUtil.toJson(event.payloadData());
        return json == null || json.isEmpty() ? EMPTY_PAYLOAD : json;
    }

    /**
     * 读取侧对称还原：占位值视为“无业务数据”，返回 null 交给各 case 的 null 分支处理
     */
    private String readPayload(AgentEventPO agentEventPO) {
        String payload = agentEventPO.getPayload();
        if (payload == null || payload.isEmpty() || EMPTY_PAYLOAD.equals(payload.trim())) {
            return null;
        }
        return payload;
    }

    private String stripEventId(String eventId) {
        if (eventId != null && eventId.startsWith(EVENT_ID_PREFIX)) {
            return eventId.substring(EVENT_ID_PREFIX.length());
        }
        return eventId;
    }

    private String buildEventId(String eventId) {
        return EVENT_ID_PREFIX + eventId;
    }
}
