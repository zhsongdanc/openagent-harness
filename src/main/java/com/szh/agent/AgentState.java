package com.szh.agent;

import com.szh.context.dto.MessageItem;
import com.szh.context.dto.SystemMessageItem;
import com.szh.event.Event;
import com.szh.event.MessageEvent;
import com.szh.store.EventStore;

import java.util.ArrayList;
import java.util.List;

/**
 * @author demussong
 * @describe
 * @date 2026/8/25 12:08
 */
public class AgentState {

    // 给到大模型的窗口
    private List<MessageItem> modelContext = new ArrayList<>();

    private EventStore eventStore;

    private int turnId = 0;

    public AgentState(EventStore eventStore){
        this.eventStore = eventStore;
        MessageItem messageItem = new SystemMessageItem("你是一个人工智能助手，请回答用户问题。下面是上下文：");
        modelContext.add(messageItem);
    }

    public List<MessageItem> getModelContext() {
        return modelContext;
    }

    public void applyEvent(Event event) {
        eventStore.appendEvent(event);
        if (event instanceof MessageEvent) {
            MessageItem messageItem = ((MessageEvent) event).getMessageItem();
            modelContext.add(messageItem);
        }
    }

    public int getTurnId() {
        return turnId;
    }

    public void incrementTurnId() {
        this.turnId++;
    }

    public EventStore getEventStore() {
        return eventStore;
    }

}
