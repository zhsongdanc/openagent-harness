package com.szh.store.db;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;

import java.util.List;
import java.util.function.Function;

/**
 * @author demussong
 * @describe agent_event 表仓储，封装 SqlSession 生命周期，业务方直接调语义化方法
 * @date 2026/9/1
 */
@Slf4j
public class AgentEventRepository {

    private final SqlSessionFactory sqlSessionFactory;

    public AgentEventRepository() {
        this(MyBatisPlusHolder.getSqlSessionFactory());
    }

    public AgentEventRepository(SqlSessionFactory sqlSessionFactory) {
        this.sqlSessionFactory = sqlSessionFactory;
    }

    public void insert(AgentEventPO po) {
        withMapper(mapper -> mapper.insert(po));
        log.debug("insert agent_event ok, id={}, eventId={}", po.getId(), po.getEventId());
    }

    public void insertBatch(List<AgentEventPO> poList) {
        if (poList == null || poList.isEmpty()) {
            return;
        }
        withMapper(mapper -> {
            poList.forEach(mapper::insert);
            return null;
        });
        log.debug("insertBatch agent_event ok, size={}", poList.size());
    }

    public AgentEventPO findById(Long id) {
        return withMapper(mapper -> mapper.selectById(id));
    }

    public List<AgentEventPO> findBySessionId(String sessionId) {
        return withMapper(mapper -> mapper.selectList(
                new LambdaQueryWrapper<AgentEventPO>()
                        .eq(AgentEventPO::getSessionId, sessionId)
                        .orderByAsc(AgentEventPO::getEventTime)
                        .orderByAsc(AgentEventPO::getId)));
    }

    public List<AgentEventPO> findByRun(String sessionId, String runId) {
        return withMapper(mapper -> mapper.selectList(
                new LambdaQueryWrapper<AgentEventPO>()
                        .eq(AgentEventPO::getSessionId, sessionId)
                        .eq(AgentEventPO::getRunId, runId)
                        .orderByAsc(AgentEventPO::getEventTime)
                        .orderByAsc(AgentEventPO::getId)));
    }

    public List<AgentEventPO> findByTurn(String sessionId, String turnId) {
        return withMapper(mapper -> mapper.selectList(
                new LambdaQueryWrapper<AgentEventPO>()
                        .eq(AgentEventPO::getSessionId, sessionId)
                        .eq(AgentEventPO::getTurnId, turnId)
                        .orderByAsc(AgentEventPO::getEventTime)
                        .orderByAsc(AgentEventPO::getId)));
    }

    public void update(AgentEventPO po) {
        withMapper(mapper -> mapper.updateById(po));
    }

    public void deleteById(Long id) {
        withMapper(mapper -> mapper.deleteById(id));
    }

    /**
     * 统一模板：开 autoCommit 的 session，执行后自动关闭
     */
    private <T> T withMapper(Function<AgentEventMapper, T> action) {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            return action.apply(session.getMapper(AgentEventMapper.class));
        }
    }
}
