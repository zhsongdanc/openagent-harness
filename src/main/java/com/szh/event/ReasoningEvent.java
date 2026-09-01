
package com.szh.event;

import com.szh.context.dto.MessageItem;

/**
 * @author demussong
 * @describe 思维链事件，messageItem 进入历史并在下轮回传模型
 * @date 2026/8/31
 */
public class ReasoningEvent extends MessageEvent {

    public ReasoningEvent(String sessionId, String turnId, MessageItem messageItem) {
        super(messageItem);
        this.sessionId = sessionId;
        this.turnId = turnId;
    }

    @Override
    public EventEnum getType() {
        return EventEnum.MODEL_REASONING;
    }
}
