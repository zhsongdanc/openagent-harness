package com.szh.agent;

import com.szh.context.dto.MessageItem;
import com.szh.context.dto.SystemMessageItem;

import java.util.ArrayList;
import java.util.List;

/**
 * @author demussong
 * @describe
 * @date 2026/8/25 12:08
 */
public class AgentState {

    // 维护用户级别对话 or 只要调用大模型就append。当前可以设计为几种类型的消息
    private List<MessageItem> histories = new ArrayList<>();

    public AgentState(){
        MessageItem messageItem = new SystemMessageItem("你是一个人工智能助手，请回答用户问题。下面是上下文：");
        histories.add(messageItem);
    }

    public void appendMsg(MessageItem messageItem) {
        histories.add(messageItem);
    }

    public List<MessageItem> getHistories() {
        return histories;
    }

}
