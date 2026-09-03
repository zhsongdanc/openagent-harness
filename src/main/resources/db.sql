event表
id sessionid runId turnId round type timestamp content ctime

snapshot表 （每个turn做，可以暂时先不做）
sessionid turnId timestamp content(json形式) ctime

-- agent_event 表新增轮次字段：round 记录事件属于 run 内的第几轮，轮次外事件为 0
ALTER TABLE agent_event
    ADD COLUMN round INT NOT NULL DEFAULT 0 COMMENT '轮次序号，run内自增，轮次外事件为0' AFTER turn_id;

