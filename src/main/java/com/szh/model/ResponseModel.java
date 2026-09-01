
package com.szh.model;

import com.szh.context.dto.MessageItem;
import com.szh.model.dto.output.ResponseModelResp;
import com.szh.tool.Tool;

import java.util.List;

/**
 * @author demussong
 * @describe Responses API 模型接口，返回多个输出项
 * @date 2026/8/31
 */
public interface ResponseModel {

    ResponseModelResp call(List<MessageItem> messages, List<Tool> tools);
}
