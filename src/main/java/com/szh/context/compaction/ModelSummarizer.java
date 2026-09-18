package com.szh.context.compaction;

import com.szh.context.dto.MessageItem;
import com.szh.model.Model;
import com.szh.model.dto.ModelResp;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.List;

/**
 * 基于 Chat Completions 风格 {@link Model} 的摘要器适配器。
 * <p>
 * 压缩是一次性文本生成，不传工具；调用失败或无内容时返回 null，由上层回退到 L0 结果。
 *
 * @author demussong
 * @date 2026/9/18
 */
@Slf4j
public class ModelSummarizer implements Summarizer {

    private final Model model;

    public ModelSummarizer(Model model) {
        this.model = model;
    }

    @Override
    public String summarize(List<MessageItem> messages) {
        try {
            ModelResp resp = model.call(messages, Collections.emptyList());
            if (resp != null && resp.getMessage() != null) {
                return resp.getMessage().getContent();
            }
        } catch (Exception e) {
            log.error("ModelSummarizer: failed to call LLM for summary", e);
        }
        return null;
    }
}
