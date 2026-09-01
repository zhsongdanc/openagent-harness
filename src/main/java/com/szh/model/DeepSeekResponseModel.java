package com.szh.model;

/**
 * @author demussong
 * @describe
 * @date 2026/8/31 19:36
 */

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.szh.context.dto.AssistantMessageItem;
import com.szh.context.dto.MessageItem;
import com.szh.context.dto.ReasoningMessageItem;
import com.szh.context.dto.ToolMessageItem;
import com.szh.model.dto.output.*;
import com.szh.tool.Tool;
import com.szh.tool.ToolDefinition;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

@Slf4j
public class DeepSeekResponseModel implements ResponseModel {

    private static final String API_URL = "https://api.deepseek.com/responses";
    private static final String DEFAULT_MODEL = "deepseek-v4-flash";

    private final String apiKey;
    private final String modelName;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    public DeepSeekResponseModel(String apiKey) {
        this(apiKey, DEFAULT_MODEL);
    }

    public DeepSeekResponseModel(String apiKey, String modelName) {
        this.apiKey = apiKey;
        this.modelName = modelName;
    }

    @Override
    public ResponseModelResp call(List<MessageItem> messages, List<Tool> tools) {
        try {
            ObjectNode request = mapper.createObjectNode();
            request.put("model", modelName);
            request.set("input", convertInput(messages));

            if (tools != null && !tools.isEmpty()) {
                request.set("tools", convertTools(tools));
                request.put("tool_choice", "auto");
            }

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(API_URL))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(request.toString()))
                    .build();

            HttpResponse<String> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.error("DeepSeek Responses API error: {}", response.body());
                throw new RuntimeException("DeepSeek Responses API error: " + response.body());
            }
            return parseResponse(response.body());

        } catch (Exception e) {
            log.error("DeepSeekResponseModel call failed", e);
            throw new RuntimeException("DeepSeekResponseModel call failed", e);
        }
    }

    /**
     * MessageItem 列表转 Responses API 的 input 数组
     *
     * 与 Chat Completions 的 messages 不同，Responses API 使用带 type 的 item：
     * - 普通消息: {type: "message", role, content}
     * - 工具调用: {type: "function_call", call_id, name, arguments}
     * - 工具结果: {type: "function_call_output", call_id, output}
     * - 思维链: {type: "reasoning", content}
     */
    private ArrayNode convertInput(List<MessageItem> items) {
        ArrayNode array = mapper.createArrayNode();

        for (MessageItem item : items) {
            if (item instanceof AssistantMessageItem assistantItem && assistantItem.isCallTool()) {
                ObjectNode functionCall = mapper.createObjectNode();
                functionCall.put("type", "function_call");
                functionCall.put("call_id", assistantItem.getToolCallId());
                functionCall.put("name", assistantItem.getToolCode());
                functionCall.put("arguments", assistantItem.getToolArgs());
                array.add(functionCall);

            } else if (item instanceof ToolMessageItem toolItem) {
                ObjectNode functionOutput = mapper.createObjectNode();
                functionOutput.put("type", "function_call_output");
                functionOutput.put("call_id", toolItem.getCallId());
                functionOutput.put("output", toolItem.getExecResult());
                array.add(functionOutput);

            } else if (item instanceof ReasoningMessageItem reasoningItem) {
                ObjectNode reasoning = mapper.createObjectNode();
                reasoning.put("type", "reasoning");
                ArrayNode contentArray = mapper.createArrayNode();
                ObjectNode contentBlock = mapper.createObjectNode();
                contentBlock.put("type", "reasoning_text");
                contentBlock.put("text", reasoningItem.getContent());
                contentArray.add(contentBlock);
                reasoning.set("content", contentArray);
                array.add(reasoning);

            } else {
                ObjectNode message = mapper.createObjectNode();
                message.put("type", "message");
                message.put("role", item.role());
                ArrayNode contentArray = mapper.createArrayNode();
                ObjectNode contentBlock = mapper.createObjectNode();
                contentBlock.put("type", "input_text");
                contentBlock.put("text", item.transfer2prompt());
                contentArray.add(contentBlock);
                message.set("content", contentArray);
                array.add(message);
            }
        }
        return array;
    }

    /**
     * Tool 列表转 Responses API 的 tools 数组
     *
     * 注意：与 Chat Completions 不同，Responses API 的工具定义是扁平结构，
     * name/description/parameters 直接在顶层，不嵌套在 "function" 对象中
     */
    private ArrayNode convertTools(List<Tool> tools) throws Exception {
        ArrayNode array = mapper.createArrayNode();

        for (Tool tool : tools) {
            ToolDefinition toolDefinition = tool.getToolDefinition();
            ObjectNode item = mapper.createObjectNode();
            item.put("type", "function");
            item.put("name", toolDefinition.getName());
            item.put("description", toolDefinition.getDescription());
            item.set("parameters", mapper.readTree(toolDefinition.getParameters()));
            array.add(item);
        }
        return array;
    }

    /**
     * 解析 Responses API 响应
     *
     * 响应结构: {output: [{type: "function_call", ...}, {type: "message", ...}, {type: "reasoning", ...}]}
     * 一轮响应可能包含多个输出项，全部解析返回
     */
    private ResponseModelResp parseResponse(String json) throws Exception {
        JsonNode root = mapper.readTree(json);
        JsonNode output = root.path("output");

        List<OutputItem> items = new ArrayList<>();
        if (output.isArray()) {
            for (JsonNode node : output) {
                items.add(parseOutputItem(node));
            }
        }

        if (items.isEmpty()) {
            throw new RuntimeException("DeepSeek Responses API returned empty output");
        }
        return new ResponseModelResp(items);
    }

    /**
     * 单个输出项按 type 解析为对应子类
     */
    private OutputItem parseOutputItem(JsonNode node) {
        String type = node.path("type").asText();
        switch (type) {
            case FunctionCallOutputItem.TYPE:
                return new FunctionCallOutputItem(
                        node.path("call_id").asText(),
                        node.path("name").asText(),
                        node.path("arguments").asText());
            case MessageOutputItem.TYPE:
                return new MessageOutputItem(extractText(node));
            case ReasoningOutputItem.TYPE:
                return new ReasoningOutputItem(extractText(node));
            default:
                return new UnknownOutputItem(type);
        }
    }

    /**
     * 从输出项的 content 中提取文本
     *
     * message 的内容块为 {type: "output_text", text}，reasoning 同样从 text 字段取值
     */
    private String extractText(JsonNode node) {
        StringBuilder text = new StringBuilder();
        JsonNode content = node.path("content");
        if (content.isArray()) {
            for (JsonNode part : content) {
                JsonNode textNode = part.path("text");
                if (!textNode.isMissingNode()) {
                    text.append(textNode.asText());
                }
            }
        } else if (content.isTextual()) {
            text.append(content.asText());
        }
        return text.toString();
    }
}

