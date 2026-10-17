package com.szh.tool.tools.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.szh.memory.GoalStatus;
import com.szh.memory.LongTermMemory;
import com.szh.memory.MemoryGoal;
import com.szh.tool.ToolContext;
import com.szh.tool.ToolDefinition;

import java.util.List;

/**
 * 活动目标工具（第三阶段）：让模型维护「尚未完成、值得跨轮跟踪」的事项。
 * <p>与普通 {@code remember}（沉淀知识）不同，目标是待办而非知识，有明确状态机
 * （{@code ACTIVE→DONE/CANCELLED/SUPERSEDED}）并按「进行中」常驻注入 prompt，助手据此知道手上还有什么没做完。
 * 单工具多动作（action 分派），对齐 todo 类工具范式。目标层不可用（memory.goals.enabled=false 或建表失败）
 * 时返回降级提示，绝不影响其它工具与主流程。
 *
 * @author demussong
 * @date 2026-10-16
 */
public class GoalTool extends MemoryToolSupport {

    public static final String CODE = "goal";

    private static final ToolDefinition TOOL_DEFINITION = ToolDefinition.builder()
            .name("goal")
            .code(CODE)
            .type("system")
            .description("维护活动目标（进行中/已完成/已取消/被取代的待办事项）。用 action 指定操作："
                    + "add 登记新目标（content 必填）；update 改标题/正文（id 必填）；"
                    + "complete/cancel/supersede 把目标置为对应终态（id 必填）；delete 物理删除（id 必填）；"
                    + "list 列出进行中的目标。进行中的目标会在每次会话开始时常驻注入上下文，"
                    + "适合登记正在开发的项目阶段、已确定的下一步、暂时搁置但仍有效的计划。")
            .parameters("{\"type\":\"object\",\"properties\":{"
                    + "\"action\":{\"type\":\"string\",\"description\":\"操作，枚举：add/update/complete/cancel/supersede/delete/list\"},"
                    + "\"content\":{\"type\":\"string\",\"description\":\"add/update 的目标正文，自包含陈述句\"},"
                    + "\"title\":{\"type\":\"string\",\"description\":\"可选，5~20 字的简短目标名\"},"
                    + "\"id\":{\"type\":\"string\",\"description\":\"update/complete/cancel/supersede/delete 必填，目标 id（来自 add/list 返回）\"}"
                    + "},\"required\":[\"action\"]}")
            .build();

    @Override
    public String getCode() {
        return CODE;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return TOOL_DEFINITION;
    }

    @Override
    public boolean inlineResult() {
        return true;
    }

    @Override
    public String execute(ToolContext toolContext) {
        LongTermMemory memory = LongTermMemory.get();
        if (!memory.isActive()) {
            return DISABLED_MSG;
        }
        if (!memory.isGoalsAvailable()) {
            return "活动目标层未启用（memory.goals.enabled=false 或存储未就绪），本次操作被忽略。";
        }
        JsonNode args = parseArgs(toolContext.getArgs());
        String action = text(args, "action");
        if (action == null) {
            return "缺少必填参数 action（add/update/complete/cancel/supersede/delete/list）";
        }
        String id = text(args, "id");
        String title = text(args, "title");
        String content = text(args, "content");

        switch (action.trim().toLowerCase()) {
            case "add": {
                if (content == null) {
                    return "add 需要 content（目标正文）";
                }
                String newId = memory.addGoal(title, content, toolContext.getSessionId());
                return newId == null ? "目标登记失败，请稍后重试。" : "已登记进行中目标（id=" + newId + "）：" + brief(title, content);
            }
            case "update": {
                if (id == null) {
                    return "update 需要 id";
                }
                return memory.updateGoal(id, title, content)
                        ? "已更新目标 id=" + id
                        : "更新失败：目标不存在或正文为空。id=" + id;
            }
            case "complete":
                return setStatus(memory, id, GoalStatus.DONE, "已完成");
            case "cancel":
                return setStatus(memory, id, GoalStatus.CANCELLED, "已取消");
            case "supersede":
                return setStatus(memory, id, GoalStatus.SUPERSEDED, "已被取代");
            case "delete": {
                if (id == null) {
                    return "delete 需要 id";
                }
                return memory.deleteGoal(id) ? "已删除目标 id=" + id : "删除失败：目标不存在。id=" + id;
            }
            case "list": {
                List<MemoryGoal> active = memory.listActiveGoals(50);
                if (active.isEmpty()) {
                    return "当前没有进行中的目标。";
                }
                StringBuilder sb = new StringBuilder("进行中的目标（" + active.size() + " 条）：\n");
                int i = 1;
                for (MemoryGoal g : active) {
                    sb.append(i++).append(". [").append(g.getId()).append("] ");
                    if (g.getTitle() != null && !g.getTitle().isBlank()) {
                        sb.append(g.getTitle()).append(" — ");
                    }
                    sb.append(g.getContent().strip()).append("\n");
                }
                return sb.toString().stripTrailing();
            }
            default:
                return "未知 action：" + action + "（可选 add/update/complete/cancel/supersede/delete/list）";
        }
    }

    private String setStatus(LongTermMemory memory, String id, GoalStatus status, String label) {
        if (id == null) {
            return label + " 需要 id";
        }
        return memory.setGoalStatus(id, status)
                ? "已标记目标为" + label + "：id=" + id
                : label + "失败：目标不存在。id=" + id;
    }

    private String brief(String title, String content) {
        String body = content.strip();
        String prefix = (title != null && !title.isBlank()) ? title.strip() + " — " : "";
        String s = prefix + body;
        return s.length() > 80 ? s.substring(0, 80) + "…" : s;
    }
}
