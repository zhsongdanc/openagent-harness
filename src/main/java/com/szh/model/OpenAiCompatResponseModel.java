package com.szh.model;

/**
 * @author demussong
 * @describe OpenAI 兼容 Responses API 模型：DeepSeek / OpenAI 的 Responses 协议同构，
 * 只需换 baseUrl / apiKey / modelName 即可切换 Provider。
 * <p>
 * 在原有阻塞调用基础上增加 SSE 流式：Responses API 的流事件带 type 字段
 * （response.reasoning_text.delta / response.output_text.delta / response.completed 等），
 * 按事件类型把增量回调给 {@link StreamListener}，同时缓存完整输出项，
 * 流结束时用 response.completed 里的完整 response 走既有解析逻辑，保证与非流式结果等价。
 * @date 2026/9/21
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
import com.szh.utils.ConfigUtil;
import com.szh.utils.RetryExecutor;
import com.szh.utils.RetryPolicy;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Slf4j
public class OpenAiCompatResponseModel implements ResponseModel {

    protected final String baseUrl;
    protected final String modelName;
    protected final String apiKey;
    /**
     * 日志与异常信息中的 Provider 标识
     */
    protected final String label;

    protected final ObjectMapper mapper = new ObjectMapper();
    protected final HttpClient client = HttpClient.newHttpClient();

    public OpenAiCompatResponseModel(String baseUrl, String modelName, String apiKey) {
        this(baseUrl, modelName, apiKey, "OpenAICompatResponses");
    }

    public OpenAiCompatResponseModel(String baseUrl, String modelName, String apiKey, String label) {
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.modelName = modelName;
        this.apiKey = apiKey;
        this.label = label;
    }

    private static String normalizeBaseUrl(String baseUrl) {
        String trimmed = baseUrl == null ? "" : baseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    @Override
    public ResponseModelResp call(List<MessageItem> messages, List<Tool> tools) {
        return call(messages, tools, null);
    }

    @Override
    public ResponseModelResp call(List<MessageItem> messages, List<Tool> tools, StreamListener listener) {
        boolean stream = listener != null && ConfigUtil.getBoolean("model.stream.enabled", true);
        try {
            if (stream) {
                // 流式路径不重试：增量已回调给 UI，重放会重复打印
                return doStreamCall(messages, tools, listener);
            }
            // 与 Chat Completions 路径一致：瞬时故障指数退避重试，确定性错误立即失败
            return RetryExecutor.execute(
                    () -> doCall(messages, tools),
                    RetryPolicy.fromConfig(),
                    OpenAiCompatModel::isRetryable,
                    label + " responses call");
        } catch (Exception e) {
            log.error("{} call failed", label, e);
            throw new RuntimeException(label + " call failed", e);
        }
    }

    /**
     * 单次阻塞请求：非 200 抛 {@link ModelApiException} 携带状态码与 Retry-After，供重试器判定
     */
    private ResponseModelResp doCall(List<MessageItem> messages, List<Tool> tools) throws Exception {
        HttpRequest httpRequest = buildRequest(messages, tools, false);

        HttpResponse<String> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            log.error("{} Responses API error: {},req:{}", label, response.body(), httpRequest.toString());
            throw new ModelApiException(response.statusCode(),
                    label + " Responses API error: " + response.body(), OpenAiCompatModel.parseRetryAfterMs(response));
        }
        return parseResponse(response.body());
    }

    /**
     * 单次流式请求（SSE）：逐行解析事件块，增量回调 listener，
     * 以 response.completed 事件中的完整 response 作为最终解析输入
     */
    private ResponseModelResp doStreamCall(List<MessageItem> messages, List<Tool> tools, StreamListener listener) throws Exception {
        HttpRequest httpRequest = buildRequest(messages, tools, true);

        HttpResponse<InputStream> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            String body = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
            log.error("{} Responses stream error: {}", label, body);
            listener.onComplete();
            throw new ModelApiException(response.statusCode(),
                    label + " Responses stream error: " + body, OpenAiCompatModel.parseRetryAfterMs(response));
        }

        try {
            return consumeSse(response.body(), listener);
        } finally {
            listener.onComplete();
        }
    }

    /**
     * 消费 Responses API 的 SSE 流：
     * - response.reasoning_text.delta / response.reasoning.delta -> onReasoningDelta
     * - response.output_text.delta -> onTextDelta
     * - response.completed -> 取完整 response 节点走既有 parseResponse，保证与非流式等价
     * 未收到 completed 事件（如中途断流）时用增量兜底构造一条 message 输出。
     */
    private ResponseModelResp consumeSse(InputStream in, StreamListener listener) throws Exception {
        StringBuilder textAccumulator = new StringBuilder();
        JsonNode completedResponse = null;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).trim();
                if (data.isEmpty() || "[DONE]".equals(data)) {
                    continue;
                }
                JsonNode event;
                try {
                    event = mapper.readTree(data);
                } catch (Exception e) {
                    log.debug("{} skip non-json sse chunk: {}", label, data);
                    continue;
                }
                String type = event.path("type").asText("");
                switch (type) {
                    case "response.reasoning_text.delta", "response.reasoning.delta" -> {
                        String delta = event.path("delta").asText("");
                        if (!delta.isEmpty()) {
                            listener.onReasoningDelta(delta);
                        }
                    }
                    case "response.output_text.delta" -> {
                        String delta = event.path("delta").asText("");
                        if (!delta.isEmpty()) {
                            textAccumulator.append(delta);
                            listener.onTextDelta(delta);
                        }
                    }
                    case "response.completed" -> completedResponse = event.get("response");
                    case "response.failed" -> {
                        String errMsg = event.path("response").path("error").path("message").asText("unknown");
                        throw new IOException(label + " stream response.failed: " + errMsg);
                    }
                    default -> {
                        // 其余生命周期事件（created/in_progress/done 等）对流式展示无意义，忽略
                    }
                }
            }
        }

        if (completedResponse != null) {
            return parseResponse(completedResponse.toString());
        }
        // 断流兜底：把已收到的正文增量拼成一条 message 输出，避免整轮丢失
        log.warn("{} stream ended without response.completed, fallback to accumulated text", label);
        List<OutputItem> items = new ArrayList<>();
        items.add(new MessageOutputItem(textAccumulator.toString()));
        return new ResponseModelResp(items);
    }

    private HttpRequest buildRequest(List<MessageItem> messages, List<Tool> tools, boolean stream) throws Exception {
        ObjectNode request = mapper.createObjectNode();
        request.put("model", modelName);
        request.set("input", convertInput(messages));

        if (tools != null && !tools.isEmpty()) {
            request.set("tools", convertTools(tools));
            request.put("tool_choice", "auto");
        }
        if (stream) {
            request.put("stream", true);
        }

        return HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/responses"))
                .timeout(Duration.ofSeconds(ConfigUtil.getInt("model.request.timeoutSeconds", 120)))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", stream ? "text/event-stream" : "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(request.toString()))
                .build();
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
    protected ArrayNode convertInput(List<MessageItem> items) {
        ArrayNode array = mapper.createArrayNode();
        List<ObjectNode> pendingOutputs = new ArrayList<>();

        for (MessageItem item : items) {
            if (item instanceof ToolMessageItem toolItem) {
                // 工具结果先缓存，等待本轮所有并行 function_call 排完再 flush
                pendingOutputs.add(buildFunctionOutput(toolItem));
            } else if (item instanceof AssistantMessageItem assistantItem && assistantItem.isCallTool()) {
                // 一轮多调用：按序展开成多个连续的 function_call，满足分组约束
                for (var call : assistantItem.effectiveToolCalls()) {
                    ObjectNode functionCall = mapper.createObjectNode();
                    functionCall.put("type", "function_call");
                    functionCall.put("call_id", call.toolCallId());
                    functionCall.put("name", call.toolCode());
                    functionCall.put("arguments", call.toolArgs());
                    array.add(functionCall);
                }
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
    protected ArrayNode convertTools(List<Tool> tools) throws Exception {
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
    protected ResponseModelResp parseResponse(String json) throws Exception {
        JsonNode root = mapper.readTree(json);
        JsonNode output = root.path("output");

        List<OutputItem> items = new ArrayList<>();
        if (output.isArray()) {
            for (JsonNode node : output) {
                items.add(parseOutputItem(node));
            }
        }

        if (items.isEmpty()) {
            throw new RuntimeException(label + " Responses API returned empty output");
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
