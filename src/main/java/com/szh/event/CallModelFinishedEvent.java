package com.szh.event;

import com.szh.model.dto.ModelResp;

/**
 * @author demussong
 * @describe
 * @date 2026/8/27 14:27
 */
public class CallModelFinishedEvent extends Event {
    private String modelRes;

    public CallModelFinishedEvent(String sessionId, String modelResp) {
        super();
        this.sessionId = sessionId;
        this.modelRes = modelRes;
    }

    public String getModelRes() {
        return modelRes;
    }

    @Override
    public EventEnum getType() {
        return EventEnum.CALL_MODEL_FINISHED;
    }
}
