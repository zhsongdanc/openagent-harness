package com.szh.store.db;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * @author demussong
 * @describe agent_event 表实体，用于持久化事件日志做 replay
 * @date 2026/9/1
 */
@Data
@TableName("agent_event")
public class AgentEventPO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String eventId;

    private String sessionId;

    private String runId;

    private String turnId;

    /**
     * 事件所属轮次序号，对应 agent_event.round，轮次外的事件为 0
     */
    private Integer round;

    private String eventType;

    private Integer eventVersion;

    private String payload;

    private Long eventTime;

    private LocalDateTime ctime;
}
