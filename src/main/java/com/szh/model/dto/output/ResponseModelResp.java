
package com.szh.model.dto.output;

import com.szh.model.dto.TokenUsage;
import com.szh.model.dto.output.OutputItem;
import lombok.Data;

import java.util.List;

/**
 * @author demussong
 * @describe Responses API 响应：一轮可包含多个输出项
 * @date 2026/8/31
 */
@Data
public class ResponseModelResp {

    private List<OutputItem> items;

    /**
     * 服务端返回的 token 用量（Responses API usage 节点）；对齐 Chat 链路的 ModelResp.tokenUsage，
     * 供 StepTrace/TokenTracker 记录。断流兜底等无 usage 的场景为 null。
     */
    private TokenUsage tokenUsage;

    public ResponseModelResp(List<OutputItem> items) {
        this.items = items;
    }

    public ResponseModelResp(List<OutputItem> items, TokenUsage tokenUsage) {
        this.items = items;
        this.tokenUsage = tokenUsage;
    }
}
