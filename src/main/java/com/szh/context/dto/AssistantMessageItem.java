package com.szh.context.dto;

import com.szh.model.dto.ROLEEnum;
import com.szh.model.dto.ToolCall;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * @author demussong
 * @describe
 * @date 2026/8/25 13:51
 */
@Data
@NoArgsConstructor
public class AssistantMessageItem implements MessageItem {

    private boolean callTool;
    private String toolCallId;
    private String toolCode;
    private String toolArgs;
    private String content;

    /**
     * 一轮回复中的全部工具调用（并行 tool_calls）。单工具调用时为单元素列表；
     * toolCallId/toolCode/toolArgs 三个标量字段保留为“第一个调用”的快照，
     * 兼容既有单工具路径（事件回放、trimIncompleteTail 等）。
     */
    private List<ToolCall> toolCalls;


    public AssistantMessageItem(String content) {
        this.content = content;
    }

    public AssistantMessageItem(String toolCallId, String toolCode, String toolArgs) {
        this.callTool = true;
        this.toolCallId = toolCallId;
        this.toolCode = toolCode;
        this.toolArgs = toolArgs;
        this.toolCalls = List.of(new ToolCall(toolCallId, toolCode, toolArgs));
    }

    /**
     * 多工具调用构造：标量字段取第一个调用，保证旧读取方仍能看到有效值
     */
    public AssistantMessageItem(List<ToolCall> toolCalls) {
        this.callTool = true;
        this.toolCalls = toolCalls == null ? new ArrayList<>() : new ArrayList<>(toolCalls);
        if (!this.toolCalls.isEmpty()) {
            ToolCall first = this.toolCalls.get(0);
            this.toolCallId = first.toolCallId();
            this.toolCode = first.toolCode();
            this.toolArgs = first.toolArgs();
        }
    }

    /**
     * 本轮全部工具调用；单工具旧路径构造的实例也能拿到单元素列表，调用方无需判空分支
     */
    public List<ToolCall> effectiveToolCalls() {
        if (toolCalls != null && !toolCalls.isEmpty()) {
            return toolCalls;
        }
        if (callTool) {
            return List.of(new ToolCall(toolCallId, toolCode, toolArgs));
        }
        return List.of();
    }


    @Override
    public String role() {
        return ROLEEnum.ASSISTANT.getRole();
    }

    @Override
    public String transfer2prompt() {
        if (callTool) {
            return "现在需要调用工具" + toolCode + "参数：" + toolArgs;
        } else {
            return content;
        }

    }
}
