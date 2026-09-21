package com.szh.store;

import com.szh.event.Event;
import com.szh.store.db.AgentEventPO;
import com.szh.store.db.AgentEventRepository;

import java.util.List;
import java.util.stream.Collectors;

/**
 * @author demussong
 * @describe MySQL 事件存储：把 {@link SerializedEvent} 拆进 agent_event 各列，读取时再还原。
 * 事件的序列化/反序列化统一委托 {@link EventJsonCodec}，与 {@code FileEventStore} 共用一套逻辑，
 * 保证不同引擎的断点恢复行为一致。
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
        SerializedEvent se = EventJsonCodec.serialize(event);
        AgentEventPO agentEventPO = new AgentEventPO();
        agentEventPO.setEventId(buildEventId(se.eventId()));
        agentEventPO.setSessionId(se.sessionId());
        agentEventPO.setRunId(se.runId());
        agentEventPO.setTurnId(se.turnId());
        agentEventPO.setRound(se.round());
        agentEventPO.setEventType(se.eventType());
        agentEventPO.setEventVersion(DEFAULT_EVENT_VERSION);
        // payload 列 NOT NULL：无业务数据时写占位 JSON
        agentEventPO.setPayload(se.payload() == null ? EMPTY_PAYLOAD : se.payload());
        agentEventPO.setEventTime(se.timestamp());
        agentEventPO.setCtime(java.time.LocalDateTime.now());

        return agentEventPO;
    }

    private Event convertToEvent(AgentEventPO agentEventPO) {
        SerializedEvent se = new SerializedEvent(
                stripEventId(agentEventPO.getEventId()),
                agentEventPO.getEventTime() == null ? 0L : agentEventPO.getEventTime(),
                agentEventPO.getSessionId(),
                agentEventPO.getRunId(),
                agentEventPO.getTurnId(),
                agentEventPO.getRound() == null ? 0 : agentEventPO.getRound(),
                agentEventPO.getEventType(),
                readPayload(agentEventPO));
        return EventJsonCodec.deserialize(se);
    }

    /**
     * 读取侧对称还原：占位值视为“无业务数据”，返回 null 交给 codec 的 null 分支处理
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
