package com.szh.agent.subagent;

import com.szh.agent.AgentResponseRuntime;
import com.szh.agent.AgentRuntime;
import com.szh.agent.AgentState;
import com.szh.model.Model;
import com.szh.model.ModelFactory;
import com.szh.model.ResponseModel;
import com.szh.store.EventStore;
import com.szh.store.EventStoreFactory;
import com.szh.tool.ToolRegistry;
import com.szh.utils.CommonUtils;
import com.szh.utils.ConfigUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 子 agent 执行器：对标 Claude Code 的 Task 工具背后的 subagent 机制。
 * <p>
 * 核心是「上下文物理隔离」——不复用主 agent 的 {@link AgentState}/modelContext，而是为每次派生
 * 建一套全新的独立运行时环境：
 * <ol>
 *   <li><b>独立 AgentState + 独立 sessionId</b>：子 agent 的 reasoning/工具调用等中间事件落到自己的
 *       session（{@code {parent}-sub-xxxx}），<b>绝不混入父事件流</b>。这是关键——父 agent 的
 *       {@code AgentState.resume()} 从事件流重建上下文，若子事件混进去，断点恢复时会把子 agent 的全部
 *       中间产物重新灌回主上下文，直接违背「主 agent 只拿最终结论」的初衷。父 agent 只通过工具结果
 *       拿到子 agent 的最终结论；</li>
 *   <li><b>独立工具子集</b>：按 {@link SubAgentType} 白名单构造 {@link ToolRegistry}，只读型子 agent
 *       拿不到写工具；</li>
 *   <li><b>深度闸门</b>：子 agent 的 ToolRegistry 以 {@code childDepth} 构造，达到 {@code subagent.maxDepth}
 *       时不再注册 dispatch_subagent，从结构上杜绝无限递归派生；</li>
 *   <li><b>更小的轮次上限</b>：子 agent 用 {@code subagent.maxRound}（默认小于主 agent），控制成本与失控风险。</li>
 * </ol>
 * 复用现有两条运行时（{@link AgentRuntime} Chat / {@link AgentResponseRuntime} Responses），
 * 通过 {@code subagent.runtime} 选择链路，Responses 不可用时自动回退 Chat。
 *
 * @author demussong
 * @date 2026/9/22
 */
public class SubAgentExecutor {

    private static final Logger log = LoggerFactory.getLogger(SubAgentExecutor.class);

    /**
     * 模型注入位：生产路径为 null（走 {@link ModelFactory} 装配）；冒烟测试注入 Fake 模型即可脱离真实 API 验证。
     */
    private final Model chatModelOverride;
    private final ResponseModel responseModelOverride;

    public SubAgentExecutor() {
        this(null, null);
    }

    public SubAgentExecutor(Model chatModelOverride, ResponseModel responseModelOverride) {
        this.chatModelOverride = chatModelOverride;
        this.responseModelOverride = responseModelOverride;
    }

    /**
     * 派生并同步跑完一个子 agent，返回其最终结论。
     *
     * @param typeCode        子 agent 类型 code（见 {@link SubAgentType}），未知/为空回退 general-purpose
     * @param prompt          交给子 agent 的任务指令（子 agent 看不到父上下文，指令必须自包含）
     * @param parentSessionId 父 session，用于派生子 sessionId 做事件隔离
     * @param parentDepth     父 agent 所处深度，主 agent 传 0
     * @return 子 agent 的最终结论文本；深度超限或执行异常时返回可读的失败原因（不抛异常，交由工具结果回传）
     */
    public String dispatch(String typeCode, String prompt, String parentSessionId, int parentDepth) {
        SubAgentType type = SubAgentType.from(typeCode);
        int maxDepth = Math.max(1, ConfigUtil.getInt("subagent.maxDepth", 1));
        int childDepth = parentDepth + 1;
        if (childDepth > maxDepth) {
            return "已达子 agent 最大嵌套深度（subagent.maxDepth=" + maxDepth + "），拒绝继续派生。";
        }

        String childSessionId = buildChildSessionId(parentSessionId);
        int maxRound = ConfigUtil.getInt("subagent.maxRound", 30);
        log.info("dispatch subagent: type={}, childSession={}, depth={}/{}, maxRound={}",
                type.getCode(), childSessionId, childDepth, maxDepth, maxRound);

        try {
            // 独立事件存储 + 独立 AgentState（角色指令追加在分层 system prompt 之后）
            EventStore store = EventStoreFactory.createEventStore();
            AgentState childState = new AgentState(store, type.rolePrompt());
            // 工具白名单 + 深度感知：childDepth 达上限时子 registry 不会再有 dispatch_subagent
            ToolRegistry childRegistry = new ToolRegistry(type.allowedCodes(), childDepth);

            if (useResponseRuntime()) {
                try {
                    ResponseModel model = responseModelOverride != null
                            ? responseModelOverride : ModelFactory.createResponseModel();
                    AgentResponseRuntime runtime = new AgentResponseRuntime(childState, childRegistry, model);
                    runtime.setMaxRound(maxRound);
                    return runtime.run(childSessionId, prompt);
                } catch (IllegalArgumentException e) {
                    // provider 不支持 Responses API：回退 Chat 链路，而非让子 agent 直接失败
                    log.warn("subagent Responses 链路不可用（{}），回退 Chat 链路", e.getMessage());
                }
            }
            Model model = chatModelOverride != null ? chatModelOverride : ModelFactory.createChatModel();
            AgentRuntime runtime = new AgentRuntime(childState, childRegistry, model);
            runtime.setMaxRound(maxRound);
            return runtime.run(childSessionId, prompt);
        } catch (RuntimeException e) {
            log.error("subagent run failed: type={}, childSession={}", type.getCode(), childSessionId, e);
            return "子 agent 执行失败：" + e.getMessage();
        }
    }

    private boolean useResponseRuntime() {
        String runtime = ConfigUtil.get("subagent.runtime", "chat");
        return "response".equalsIgnoreCase(runtime == null ? "" : runtime.trim());
    }

    /**
     * 子 sessionId 派生：{@code {parent}-sub-{8位短id}}。父为空时省略前缀。
     * 用短横线而非冒号，兼容 FILE 引擎把 sessionId 直接当文件名的场景。
     */
    private String buildChildSessionId(String parentSessionId) {
        String suffix = "sub-" + CommonUtils.generateId().substring(0, 8);
        return parentSessionId == null || parentSessionId.isBlank()
                ? suffix
                : parentSessionId + "-" + suffix;
    }
}
