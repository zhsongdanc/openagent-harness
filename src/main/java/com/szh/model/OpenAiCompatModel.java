package com.szh.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.szh.context.dto.AssistantMessageItem;
import com.szh.context.dto.MessageItem;
import com.szh.context.dto.ToolMessageItem;
import com.szh.model.dto.ActionEnum;
import com.szh.model.dto.ModelResp;
import com.szh.model.dto.TokenUsage;
import com.szh.model.dto.ToolCall;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author demussong
 * @describe OpenAI 兼容 Chat Completions 模型：DeepSeek / OpenAI / 以及 Ollama、vLLM、LM Studio
 * 等本地推理服务都实现同一套 HTTP 协议，只需换 baseUrl / apiKey / modelName 三个参数即可接入，
 * 消除单一 Provider 锁定。
 * <p>
 * 能力：
 * <ol>
 *   <li>阻塞调用：非流式请求，非 200 抛 {@link ModelApiException} 走指数退避重试；</li>
 *   <li>流式调用（SSE）：stream=true 逐块解析 delta 回调 {@link StreamListener}，
 *       正文/思维链/工具调用参数三路增量分别聚合；流式中途失败无法重放已打印的增量，
 *       因此不做重试，直接抛错；</li>
 *   <li>并行工具调用：完整解析一轮回复中的所有 tool_calls（而非只取第一个），
 *       历史回传时也按数组还原，配合运行时并发执行。</li>
 * </ol>
 * @date 2026/9/21
 */
@Slf4j
public class OpenAiCompatModel implements Model {

    protected final String baseUrl;
    protected final String modelName;
    protected final String apiKey;
    /**
     * 日志与异常信息中的 Provider 标识（如 DeepSeek / OpenAI）
     */
    protected final String label;

    protected final ObjectMapper mapper = new ObjectMapper();

    protected final HttpClient client = HttpClient.newHttpClient();

    public OpenAiCompatModel(String baseUrl, String modelName, String apiKey) {
        this(baseUrl, modelName, apiKey, "OpenAICompat");
    }

    public OpenAiCompatModel(String baseUrl, String modelName, String apiKey, String label) {
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.modelName = modelName;
        this.apiKey = apiKey;
        this.label = label;
    }

    /**
     * 去掉尾部 '/'，拼接时统一补，避免 baseUrl 配置口径差异产生 '//chat/completions'
     */
    private static String normalizeBaseUrl(String baseUrl) {
        String trimmed = baseUrl == null ? "" : baseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    @Override
    public ModelResp call(List<MessageItem> messages, List<Tool> tools) {
        return call(messages, tools, null);
    }

    @Override
    public ModelResp call(List<MessageItem> messages, List<Tool> tools, StreamListener listener) {
        boolean stream = listener != null && ConfigUtil.getBoolean("model.stream.enabled", true);
        try {
            if (stream) {
                // 流式路径不重试：增量已经回调给 UI，重放会导致重复打印
                return doStreamCall(messages, tools, listener);
            }
            // 瞬时故障（限流/网关错误/网络抖动）按策略指数退避重试，确定性错误（4xx）立即失败
            return RetryExecutor.execute(
                    () -> doCall(messages, tools),
                    RetryPolicy.fromConfig(),
                    OpenAiCompatModel::isRetryable,
                    label + " chat call");
        } catch (Exception e) {
            log.error("{} call failed", label, e);
            throw new RuntimeException(label + " call failed", e);
        }
    }

    /**
     * 单次阻塞请求：非 200 抛 {@link ModelApiException} 携带状态码与 Retry-After，供重试器判定
     */
    private ModelResp doCall(List<MessageItem> messages, List<Tool> tools) throws Exception {
        HttpRequest httpRequest = buildRequest(messages, tools, false);

        HttpResponse<String> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            log.error("{} error: {}", label, response.body());
            throw new ModelApiException(response.statusCode(),
                    label + " error:" + response.body(), parseRetryAfterMs(response));
        }
        return parseResponse(response.body());
    }

    /**
     * 单次流式请求（SSE）：逐行解析 data: 块，增量回调 listener，聚合出与非流式等价的 ModelResp。
     * 请求带 stream_options.include_usage，最后一个数据块携带 token 用量。
     */
    private ModelResp doStreamCall(List<MessageItem> messages, List<Tool> tools, StreamListener listener) throws Exception {
        HttpRequest httpRequest = buildRequest(messages, tools, true);

        HttpResponse<InputStream> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            String body = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
            log.error("{} stream error: {}", label, body);
            listener.onComplete();
            throw new ModelApiException(response.statusCode(),
                    label + " stream error: " + body, parseRetryAfterMs(response));
        }

        try {
            return consumeSse(response.body(), listener);
        } finally {
            listener.onComplete();
        }
    }

    /**
     * 消费 SSE 字节流：按行读取，"data: " 前缀取 JSON，"[DONE]" 结束。
     * 聚合三路增量：正文 content、思维链 reasoning_content、工具调用片段（按 index 归并，
     * arguments 是跨块拼接的 JSON 字符串碎片）。
     */
    private ModelResp consumeSse(InputStream in, StreamListener listener) throws IOException {
        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        // index -> [id, name, arguments]，OpenAI 兼容协议按 index 区分并行的多个 tool_call
        Map<Integer, String[]> toolCallBuffers = new HashMap<>();
        TokenUsage usage = null;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).trim();
                if (data.isEmpty()) {
                    continue;
                }
                if ("[DONE]".equals(data)) {
                    break;
                }
                JsonNode chunk;
                try {
                    chunk = mapper.readTree(data);
                } catch (Exception e) {
                    // 个别网关会夹杂非 JSON 的心跳/注释块，跳过不中断整个流
                    log.debug("{} skip non-json sse chunk: {}", label, data);
                    continue;
                }
                JsonNode usageNode = chunk.get("usage");
                if (usageNode != null && !usageNode.isNull()) {
                    usage = parseTokenUsage(usageNode);
                }
                JsonNode choices = chunk.path("choices");
                if (!choices.isArray() || choices.isEmpty()) {
                    continue;
                }
                JsonNode delta = choices.get(0).path("delta");

                String reasoningDelta = delta.path("reasoning_content").asText("");
                if (!reasoningDelta.isEmpty()) {
                    reasoning.append(reasoningDelta);
                    listener.onReasoningDelta(reasoningDelta);
                }
                String textDelta = delta.path("content").asText("");
                if (!textDelta.isEmpty()) {
                    content.append(textDelta);
                    listener.onTextDelta(textDelta);
                }
                JsonNode toolCalls = delta.get("tool_calls");
                if (toolCalls != null && toolCalls.isArray()) {
                    for (JsonNode tc : toolCalls) {
                        mergeToolCallDelta(toolCallBuffers, tc);
                    }
                }
            }
        }
        return buildAggregatedResponse(content.toString(), reasoning.toString(), toolCallBuffers, usage);
    }

    /**
     * 归并单个 tool_call 增量片段：id/name 只在首块出现，arguments 逐块拼接
     */
    private void mergeToolCallDelta(Map<Integer, String[]> buffers, JsonNode tc) {
        int index = tc.path("index").asInt(0);
        String[] buf = buffers.computeIfAbsent(index, k -> new String[3]);
        String id = tc.path("id").asText(null);
        if (id != null && !id.isEmpty()) {
            buf[0] = id;
        }
        JsonNode function = tc.path("function");
        String name = function.path("name").asText(null);
        if (name != null && !name.isEmpty()) {
            buf[1] = name;
        }
        String argsDelta = function.path("arguments").asText(null);
        if (argsDelta != null && !argsDelta.isEmpty()) {
            buf[2] = (buf[2] == null ? "" : buf[2]) + argsDelta;
        }
    }

    /**
     * 聚合结果转 ModelResp：有工具调用则构造多调用 AssistantMessageItem，否则为最终回答
     */
    private ModelResp buildAggregatedResponse(String content, String reasoning,
                                              Map<Integer, String[]> toolCallBuffers, TokenUsage usage) {
        ModelResp resp;
        if (!toolCallBuffers.isEmpty()) {
            List<ToolCall> calls = new ArrayList<>();
            toolCallBuffers.keySet().stream().sorted().forEach(index -> {
                String[] buf = toolCallBuffers.get(index);
                calls.add(new ToolCall(buf[0], buf[1], buf[2] == null ? "" : buf[2]));
            });
            resp = new ModelResp(new AssistantMessageItem(calls), ActionEnum.TOOL_CALL);
        } else {
            resp = new ModelResp(new AssistantMessageItem(content), ActionEnum.FINAL_ANSWER);
        }
        resp.setTokenUsage(usage);
        return resp;
    }

    private HttpRequest buildRequest(List<MessageItem> messages, List<Tool> tools, boolean stream) throws Exception {
        ObjectNode request = mapper.createObjectNode();
        request.put("model", modelName);
        request.set("messages", convertMessages(messages));

        if (tools != null && !tools.isEmpty()) {
            request.set("tools", convertTools(tools));
            request.put("tool_choice", "auto");
        }
        if (stream) {
            request.put("stream", true);
            // 让服务端在流的最后一块回传 usage，否则流式拿不到 token 用量
            ObjectNode streamOptions = mapper.createObjectNode();
            streamOptions.put("include_usage", true);
            request.set("stream_options", streamOptions);
        }

        return HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .timeout(Duration.ofSeconds(ConfigUtil.getInt("model.request.timeoutSeconds", 120)))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", stream ? "text/event-stream" : "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(request.toString()))
                .build();
    }

    /**
     * 判定异常是否可重试：网络类 IO 异常，或可重试状态码的 API 异常
     */
    public static boolean isRetryable(Exception e) {
        if (e instanceof ModelApiException mae) {
            return mae.isRetryable();
        }
        return e instanceof IOException;
    }

    /**
     * 解析服务端 Retry-After 响应头（秒）为毫秒，缺失或非法返回 0
     */
    public static long parseRetryAfterMs(HttpResponse<?> response) {
        return response.headers().firstValue("Retry-After")
                .map(v -> {
                    try {
                        return (long) (Double.parseDouble(v.trim()) * 1000);
                    } catch (NumberFormatException e) {
                        return 0L;
                    }
                })
                .orElse(0L);
    }

    /**
     * MessageItem 转 OpenAI messages 数组。
     * 工具调用消息按 tool_calls 数组回传（支持一轮多调用），与 parseResponse 的解析口径对称。
     */
    protected ArrayNode convertMessages(List<MessageItem> items) {
        ArrayNode array = mapper.createArrayNode();

        for (MessageItem item : items) {
            ObjectNode message = mapper.createObjectNode();
            message.put("role", item.role());
            message.put("content", item.transfer2prompt());

            if (item instanceof AssistantMessageItem assistantItem && assistantItem.isCallTool()) {
                message.putNull("content");
                ArrayNode toolCallsArray = mapper.createArrayNode();
                for (ToolCall call : assistantItem.effectiveToolCalls()) {
                    ObjectNode toolCall = mapper.createObjectNode();
                    toolCall.put("id", call.toolCallId());
                    toolCall.put("type", "function");
                    ObjectNode function = mapper.createObjectNode();
                    function.put("name", call.toolCode());
                    function.put("arguments", call.toolArgs());
                    toolCall.set("function", function);
                    toolCallsArray.add(toolCall);
                }
                message.set("tool_calls", toolCallsArray);
            }

            if (item instanceof ToolMessageItem toolItem) {
                message.put("tool_call_id", toolItem.getCallId());
            }

            array.add(message);
        }
        return array;
    }

    /**
     * Tool Definition 转 OpenAI function schema
     */
    protected ArrayNode convertTools(List<Tool> tools) throws Exception {
        ArrayNode array = mapper.createArrayNode();

        for (Tool tool : tools) {
            ToolDefinition toolDefinition = tool.getToolDefinition();
            ObjectNode function = mapper.createObjectNode();

            function.put("name", toolDefinition.getName());
            function.put("description", toolDefinition.getDescription());
            function.set("parameters", mapper.readTree(toolDefinition.getParameters()));

            ObjectNode item = mapper.createObjectNode();
            item.put("type", "function");
            item.set("function", function);
            array.add(item);
        }
        return array;
    }

    /**
     * 非流式响应转 ModelResp：完整解析所有 tool_calls（并行工具调用），不再只取第一个
     */
    protected ModelResp parseResponse(String json) throws Exception {
        JsonNode root = mapper.readTree(json);
        JsonNode message = root.path("choices").get(0).path("message");

        TokenUsage tokenUsage = parseTokenUsage(root.path("usage"));

        JsonNode toolCalls = message.get("tool_calls");
        if (toolCalls != null && toolCalls.size() > 0) {
            List<ToolCall> calls = new ArrayList<>();
            for (JsonNode tc : toolCalls) {
                JsonNode function = tc.get("function");
                calls.add(new ToolCall(
                        tc.path("id").asText(),
                        function.path("name").asText(),
                        function.path("arguments").asText()));
            }
            ModelResp resp = new ModelResp(new AssistantMessageItem(calls), ActionEnum.TOOL_CALL);
            resp.setTokenUsage(tokenUsage);
            return resp;
        }

        ModelResp resp = new ModelResp(new AssistantMessageItem(message.path("content").asText("")),
                ActionEnum.FINAL_ANSWER);
        resp.setTokenUsage(tokenUsage);
        return resp;
    }

    /**
     * 解析服务端返回的 usage 节点
     */
    protected TokenUsage parseTokenUsage(JsonNode usage) {
        if (usage == null || usage.isMissingNode() || usage.isNull()) {
            return null;
        }
        int promptTokens = usage.path("prompt_tokens").asInt(0);
        int completionTokens = usage.path("completion_tokens").asInt(0);
        int totalTokens = usage.path("total_tokens").asInt(0);
        int cachedTokens = usage.path("prompt_tokens_details").path("cached_tokens").asInt(0);
        return new TokenUsage(promptTokens, completionTokens, totalTokens, cachedTokens);
    }
}
