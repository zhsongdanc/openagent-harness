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
                log.error("DeepSeek Responses API error: {},req:{}", response.body(), request.toString());
                throw new RuntimeException("DeepSeek Responses API error: " + response.body());
            }
            String body = response.body();
            return parseResponse(body);

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
     *
     * 注意：DeepSeek 要求同一轮内所有 function_call 必须连续排在一起、
     * function_call_output 统一跟在其后，不能交错。否则下一次请求会报
     * "The reasoning_text in the thinking mode must be passed back to the API"
     * （这条报错信息具有误导性，实际是顺序问题）。
     * 这里先把 function_call_output 缓存起来，遇到非 function_call/output 的 item 时 flush。
     * function_call 与 function_call_output 通过 call_id 关联，位置重排不影响配对。
     *
     * 空 reasoning 跳过不回传（API 会拒绝）。
     */
    private ArrayNode convertInput(List<MessageItem> items) {
        ArrayNode array = mapper.createArrayNode();
        List<ObjectNode> pendingOutputs = new ArrayList<>();

        for (MessageItem item : items) {
            if (item instanceof ToolMessageItem toolItem) {
                // 工具结果先缓存，等待本轮所有并行 function_call 排完再 flush
                pendingOutputs.add(buildFunctionOutput(toolItem));
            } else if (item instanceof AssistantMessageItem assistantItem && assistantItem.isCallTool()) {
                array.add(buildFunctionCall(assistantItem));
            } else {
                // 遇到非 function_call/output（reasoning / user / system / assistant 文本），
                // 先把之前缓冲的 function_call_output flush 出去，保证顺序合规
                flushPendingOutputs(array, pendingOutputs);
                if (item instanceof ReasoningMessageItem reasoningItem) {
                    if (reasoningItem.getContent() != null && !reasoningItem.getContent().isEmpty()) {
                        array.add(buildReasoning(reasoningItem));
                    } else {
                        log.warn("跳过空 reasoning item，不回传");
                    }
                } else {
                    array.add(buildMessage(item));
                }
            }
        }
        flushPendingOutputs(array, pendingOutputs);

        log.debug("convertInput: {} items -> {} input entries", items.size(), array.size());
        return array;
    }

    private void flushPendingOutputs(ArrayNode array, List<ObjectNode> pendingOutputs) {
        for (ObjectNode output : pendingOutputs) {
            array.add(output);
        }
        pendingOutputs.clear();
    }

    private ObjectNode buildFunctionCall(AssistantMessageItem assistantItem) {
        ObjectNode functionCall = mapper.createObjectNode();
        functionCall.put("type", "function_call");
        functionCall.put("call_id", assistantItem.getToolCallId());
        functionCall.put("name", assistantItem.getToolCode());
        functionCall.put("arguments", assistantItem.getToolArgs());
        return functionCall;
    }

    private ObjectNode buildFunctionOutput(ToolMessageItem toolItem) {
        ObjectNode functionOutput = mapper.createObjectNode();
        functionOutput.put("type", "function_call_output");
        functionOutput.put("call_id", toolItem.getCallId());
        functionOutput.put("output", toolItem.getExecResult());
        return functionOutput;
    }

    private ObjectNode buildReasoning(ReasoningMessageItem reasoningItem) {
        ObjectNode reasoning = mapper.createObjectNode();
        reasoning.put("type", "reasoning");
        if (reasoningItem.getRawContentJson() != null && !reasoningItem.getRawContentJson().isEmpty()) {
            // 原样透传 API 返回的 content（含 encrypted_content 等）
            try {
                reasoning.set("content", mapper.readTree(reasoningItem.getRawContentJson()));
            } catch (Exception e) {
                log.warn("rawContentJson 解析失败，回退为明文构造", e);
                reasoning.set("content", buildPlainTextContent(reasoningItem.getContent()));
            }
        } else {
            reasoning.set("content", buildPlainTextContent(reasoningItem.getContent()));
        }
        return reasoning;
    }

    private ArrayNode buildPlainTextContent(String text) {
        ArrayNode contentArray = mapper.createArrayNode();
        ObjectNode block = mapper.createObjectNode();
        block.put("type", "reasoning_text");
        block.put("text", text != null ? text : "");
        contentArray.add(block);
        return contentArray;
    }

    private ObjectNode buildMessage(MessageItem item) {
        ObjectNode message = mapper.createObjectNode();
        message.put("type", "message");
        message.put("role", item.role());
        ArrayNode contentArray = mapper.createArrayNode();
        ObjectNode contentBlock = mapper.createObjectNode();
        contentBlock.put("type", "input_text");
        contentBlock.put("text", item.transfer2prompt());
        contentArray.add(contentBlock);
        message.set("content", contentArray);
        return message;
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

        // 日志：输出本轮返回的 item 类型，便于排查 reasoning 是否正确解析
        StringBuilder typesSummary = new StringBuilder();
        for (OutputItem item : items) {
            typesSummary.append(item.type()).append(",");
        }
        log.info("Model returned {} output items: [{}]", items.size(), typesSummary);

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
                JsonNode contentNode = node.get("content");
                String rawJson = (contentNode != null) ? contentNode.toString() : null;
                return new ReasoningOutputItem(extractText(node), rawJson);
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

