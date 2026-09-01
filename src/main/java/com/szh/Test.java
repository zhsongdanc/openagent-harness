package com.szh;

import com.szh.agent.AgentExecutor;
import com.szh.agent.AgentResponseExecutor;
import com.szh.context.dto.UserMessageItem;
import com.szh.model.DeepSeekResponseModel;
import com.szh.model.dto.output.ResponseModelResp;
import com.szh.store.db.AgentEventPO;
import com.szh.store.db.AgentEventRepository;
import com.szh.tool.ToolRegistry;
import com.szh.utils.CommonUtils;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.List;

/**
 * @author demussong
 * @describe
 * @date 2026/8/24 17:47
 */

@Slf4j
public class Test {
    public static void main(String[] args) {

        testEventCrud();
    }

    public static void testAgentRuntime() {
        String userInput = "我的ip是10.13.12.15,帮我查询一下当前天气";
        AgentExecutor agentExecutor = new AgentExecutor();
        String reply = agentExecutor.run(userInput);
        System.out.println(reply);
    }

    public static void testResponseAgent() {
        String userInput = "我的ip是10.13.12.15,帮我查询一下当前天气";
        AgentResponseExecutor agentExecutor = new AgentResponseExecutor();
        String reply = agentExecutor.run(userInput);
        System.out.println(reply);
    }

    public static void testModel() {
        DeepSeekResponseModel model = new DeepSeekResponseModel(System.getenv("DEEPSEEK_API_KEY"));

        ToolRegistry toolRegistry = new ToolRegistry();

        ResponseModelResp responseModelResp = model.call(List.of(new UserMessageItem("我的ip是10.13.12.15,帮我查询一下当前天气")),
                toolRegistry.getTools());
        System.out.println("success");
    }

    public static void testApiKey() {
        log.info("print log test");
        System.out.println(System.getenv("DEEPSEEK_API_KEY"));
    }

    public static void testEventCrud() {
        AgentEventRepository repository = new AgentEventRepository();

        String sessionId = "session-" + CommonUtils.generateId();

        // 1. insert
        AgentEventPO po = new AgentEventPO();
        po.setEventId(CommonUtils.generateId());
        po.setSessionId(sessionId);
        po.setRunId(CommonUtils.generateId());
        po.setTurnId(CommonUtils.generateId());
        po.setEventType("USER_MESSAGE");
        po.setEventVersion(1);
        po.setPayload("{\"content\":\"hello mysql\"}");
        po.setEventTime(System.currentTimeMillis());
        po.setCtime(LocalDateTime.now());
        repository.insert(po);
        log.info("[insert] ok, id={}", po.getId());

        // 2. select
        AgentEventPO fetched = repository.findById(po.getId());
        log.info("[selectById] result: {}", fetched);

        // 3. update
        fetched.setPayload("{\"content\":\"updated payload\"}");
        repository.update(fetched);
        log.info("[updateById] ok, payload={}", repository.findById(po.getId()).getPayload());

        // 4. 按 sessionId 查询
        List<AgentEventPO> list = repository.findBySessionId(sessionId);
        log.info("[findBySessionId] size={}", list.size());

        // 5. delete 并验证
        repository.deleteById(po.getId());
        log.info("[deleteById] ok, after delete findById={}", repository.findById(po.getId()));
    }
}
