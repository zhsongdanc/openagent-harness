package com.szh.context.compaction;

import com.szh.context.dto.MessageItem;
import com.szh.model.ResponseModel;
import com.szh.model.dto.output.MessageOutputItem;
import com.szh.model.dto.output.OutputItem;
import com.szh.model.dto.output.ResponseModelResp;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.List;

/**
 * 基于 Responses API 风格 {@link ResponseModel} 的摘要器适配器。
 * <p>
 * Responses API 一轮可能返回多个输出项（reasoning/message/function_call），
 * 摘要场景只关心文本输出，因此这里仅提取 {@link MessageOutputItem} 的内容拼接返回，
 * 忽略 reasoning 等其它项。压缩不传工具；调用失败或无文本时返回 null，由上层回退到 L0 结果。
 *
 * @author demussong
 * @date 2026/9/18
 */
@Slf4j
public class ResponseModelSummarizer implements Summarizer {

    private final ResponseModel model;

    public ResponseModelSummarizer(ResponseModel model) {
        this.model = model;
    }

    @Override
    public String summarize(List<MessageItem> messages) {
        try {
            ResponseModelResp resp = model.call(messages, Collections.emptyList());
            if (resp == null || resp.getItems() == null) {
                return null;
            }
            StringBuilder text = new StringBuilder();
            for (OutputItem item : resp.getItems()) {
                if (item instanceof MessageOutputItem messageItem) {
                    text.append(messageItem.getContent());
                }
            }
            return text.length() > 0 ? text.toString() : null;
        } catch (Exception e) {
            log.error("ResponseModelSummarizer: failed to call LLM for summary", e);
        }
        return null;
    }
}
