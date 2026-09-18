package com.szh.context.compaction;

import com.szh.context.dto.MessageItem;

import java.util.List;

/**
 * 摘要器抽象：输入待压缩的消息，返回 LLM 生成的摘要文本。
 * <p>
 * 目的是让上下文压缩模块与具体的模型协议（Chat Completions / Responses API）解耦：
 * 压缩本质是一次性的「消息进、文本出」调用，不需要工具、reasoning 回传等能力，
 * 因此这里只暴露最小接口，由不同协议的适配器分别实现。
 *
 * @author demussong
 * @date 2026/9/18
 */
public interface Summarizer {

    /**
     * 将一组消息交给 LLM 生成摘要文本。
     *
     * @param messages 已构建好的摘要请求消息（通常含压缩指令 system prompt + 待压缩历史）
     * @return 摘要纯文本；调用失败或无有效内容时返回 null
     */
    String summarize(List<MessageItem> messages);
}
