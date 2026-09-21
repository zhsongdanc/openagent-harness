package com.szh.mcp.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.szh.mcp.config.McpServerConfig;
import com.szh.mcp.protocol.JsonRpc;
import com.szh.mcp.protocol.McpException;
import com.szh.mcp.transport.McpTransport;
import com.szh.mcp.transport.StdioTransport;
import com.szh.tool.security.PermissionDecision;
import com.szh.tool.security.ShellSecurity;
import com.szh.utils.ConfigUtil;
import com.szh.utils.JsonUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * @author demussong
 * @describe 单个 MCP Server 的客户端：负责子进程生命周期 + JSON-RPC 请求响应关联 + tools 缓存。
 * <p>
 * 生命周期：
 * <ol>
 *   <li>{@link #connect()}：权限闸门 -> 启动 stdio 子进程 -> initialize 握手 -> tools/list 缓存；</li>
 *   <li>{@link #callTool(String, String)}：转发模型的工具调用，把 MCP content[] 展平成字符串回传；</li>
 *   <li>{@link #close()}：幂等，关子进程 + 兜底唤醒 pending。</li>
 * </ol>
 * 关键设计：
 * <ul>
 *   <li>Server 启动命令走 {@link ShellSecurity#authorize} 但默认<b>不</b>走沙箱包装
 *       （{@code sandbox=true} 才包一层），因为 MCP Server 常要写 {@code ~/.npm}、{@code ~/.cache} 等目录，
 *       沙箱档位若没配好反而启动失败；用户显式开启才 wrap；</li>
 *   <li>连接失败不抛给 Manager 让它整体崩，而是把异常暴露给 {@code lastError}，Manager 会记入 failure 列表；</li>
 *   <li>{@code tools/list} 支持分页 cursor，一次性拉完（当前主流 Server 都不分页，但要防未来）；</li>
 *   <li>请求-响应关联走 {@code CompletableFuture} + {@link McpTransport#registerPending}，超时用 {@code future.get(timeout)}；</li>
 *   <li>反向 request（sampling、roots）当前不支持，由 StdioTransport 自动回 method_not_found，避免 Server 卡等。</li>
 * </ul>
 * @date 2026/9/22
 */
@Slf4j
public class McpClient implements McpTransport.Listener {

    /** MCP 协议版本，参考 spec 2025-06-18；Server 若不支持会回自己支持的版本，握手仍成立 */
    private static final String PROTOCOL_VERSION = "2025-06-18";

    /** 兜底协议版本，老 Server 只认 2024-11-05 时用 */
    private static final String FALLBACK_PROTOCOL_VERSION = "2024-11-05";

    private static final String CLIENT_NAME = "openagent-harness";
    private static final String CLIENT_VERSION = "1.0";

    private final McpServerConfig config;
    private final String workspace;
    private final long connectTimeoutSec;
    private final long rpcTimeoutSec;

    private volatile McpTransport transport;
    private volatile List<McpToolInfo> tools = List.of();
    private volatile String serverInfo;
    private volatile String negotiatedProtocolVersion;
    private volatile String lastError;
    private volatile boolean closed;

    public McpClient(McpServerConfig config, String workspace) {
        this.config = config;
        this.workspace = workspace;
        this.connectTimeoutSec = config.getConnectTimeoutSeconds() > 0
                ? config.getConnectTimeoutSeconds()
                : ConfigUtil.getInt("mcp.connect.timeoutSeconds", 20);
        this.rpcTimeoutSec = config.getRpcTimeoutSeconds() > 0
                ? config.getRpcTimeoutSeconds()
                : ConfigUtil.getInt("mcp.rpc.timeoutSeconds", 60);
    }

    public String getServerName() {
        return config.getName();
    }

    public List<McpToolInfo> getTools() {
        return tools;
    }

    public String getServerInfo() {
        return serverInfo;
    }

    public String getNegotiatedProtocolVersion() {
        return negotiatedProtocolVersion;
    }

    public String getLastError() {
        return lastError;
    }

    public boolean isConnected() {
        return transport != null && !transport.isClosed() && !closed;
    }

    /**
     * 启动子进程 + 握手 + 拉工具列表；任一环节失败抛异常，由 Manager 捕获记录
     */
    public void connect() throws Exception {
        List<String> command = new ArrayList<>();
        command.add(config.getCommand());
        if (config.getArgs() != null) {
            command.addAll(config.getArgs());
        }
        // 权限闸门：MCP Server 是用户显式配置的可信命令，但仍过一遍分类器，防止配置文件里被塞危险命令
        PermissionDecision decision = ShellSecurity.authorize(command, workspace);
        if (!decision.isAllowed()) {
            throw new IllegalStateException("mcp server [" + config.getName() + "] 启动被安全策略拦截: "
                    + decision.getReason());
        }
        List<String> execCommand = config.isSandbox()
                ? ShellSecurity.wrap(command, workspace)
                : command;

        String workdir = config.getCwd() != null && !config.getCwd().isBlank() ? config.getCwd() : workspace;
        StdioTransport stdio = new StdioTransport(config.getName(), execCommand, config.getEnv(), workdir, this);
        this.transport = stdio;

        // initialize 握手
        ObjectNode params = JsonUtil.getMapper().createObjectNode();
        params.put("protocolVersion", PROTOCOL_VERSION);
        ObjectNode caps = params.putObject("capabilities");
        // 明确声明我们不实现 sampling / roots，避免 Server 尝试反向调用
        caps.putObject("tools");
        ObjectNode clientInfo = params.putObject("clientInfo");
        clientInfo.put("name", CLIENT_NAME);
        clientInfo.put("version", CLIENT_VERSION);

        JsonNode initResp;
        try {
            initResp = sendRequest("initialize", params, connectTimeoutSec);
        } catch (McpException e) {
            // 部分老 Server 只认 2024-11-05；重试一次
            if (e.getMessage() != null && e.getMessage().contains("protocolVersion")) {
                params.put("protocolVersion", FALLBACK_PROTOCOL_VERSION);
                initResp = sendRequest("initialize", params, connectTimeoutSec);
            } else {
                throw e;
            }
        }
        JsonNode initResult = initResp.path("result");
        this.negotiatedProtocolVersion = initResult.path("protocolVersion").asText(PROTOCOL_VERSION);
        JsonNode srv = initResult.path("serverInfo");
        this.serverInfo = srv.path("name").asText("?") + "@" + srv.path("version").asText("?");

        // 通知 Server 握手完成（必须在 tools/list 之前发）
        sendNotification("notifications/initialized", null);

        // 拉工具列表，处理分页
        List<McpToolInfo> collected = new ArrayList<>();
        String cursor = null;
        int guard = 0;
        while (guard++ < 32) {
            ObjectNode listParams = JsonUtil.getMapper().createObjectNode();
            if (cursor != null) {
                listParams.put("cursor", cursor);
            }
            JsonNode listResp = sendRequest("tools/list", listParams, rpcTimeoutSec);
            JsonNode result = listResp.path("result");
            JsonNode arr = result.path("tools");
            if (arr.isArray()) {
                for (JsonNode t : arr) {
                    McpToolInfo info = new McpToolInfo();
                    info.setName(t.path("name").asText(""));
                    info.setDescription(t.path("description").asText(""));
                    JsonNode schema = t.path("inputSchema");
                    info.setInputSchemaJson(schema.isMissingNode() || schema.isNull()
                            ? "{\"type\":\"object\",\"properties\":{}}"
                            : schema.toString());
                    if (!info.getName().isBlank()) {
                        collected.add(info);
                    }
                }
            }
            JsonNode nextCursor = result.path("nextCursor");
            if (!nextCursor.isTextual() || nextCursor.asText().isBlank()) {
                break;
            }
            cursor = nextCursor.asText();
        }
        this.tools = List.copyOf(collected);
        log.info("mcp[{}] connected: server={}, protocol={}, tools={}",
                config.getName(), serverInfo, negotiatedProtocolVersion, tools.size());
    }

    /**
     * 调 MCP 工具：把模型传来的 JSON 参数原样透传给 Server，把 content[] 展平成文本回传
     */
    public String callTool(String toolName, String argsJson) {
        if (!isConnected()) {
            return "mcp[" + config.getName() + "] not connected: "
                    + (lastError == null ? "transport closed" : lastError);
        }
        try {
            ObjectNode params = JsonUtil.getMapper().createObjectNode();
            params.put("name", toolName);
            JsonNode args;
            if (argsJson == null || argsJson.isBlank()) {
                args = JsonUtil.getMapper().createObjectNode();
            } else {
                try {
                    args = JsonUtil.getMapper().readTree(argsJson);
                } catch (Exception e) {
                    return "mcp[" + config.getName() + "] arguments 不是合法 JSON: " + e.getMessage();
                }
            }
            params.set("arguments", args);
            JsonNode resp = sendRequest("tools/call", params, rpcTimeoutSec);
            JsonNode result = resp.path("result");
            boolean isError = result.path("isError").asBoolean(false);
            String text = flattenContent(result.path("content"));
            // MCP 规范：工具业务失败通过 isError=true 标记；这里回传带前缀让 LoopGuard 也能识别为失败
            return isError ? "mcp tool error: " + text : text;
        } catch (TimeoutException e) {
            log.warn("mcp[{}] call tool {} timeout after {}s", config.getName(), toolName, rpcTimeoutSec);
            return "mcp[" + config.getName() + "] call " + toolName + " timeout after " + rpcTimeoutSec + "s";
        } catch (McpException e) {
            log.warn("mcp[{}] call tool {} rpc error: {}", config.getName(), toolName, e.getMessage());
            return "mcp[" + config.getName() + "] rpc error: " + e.getMessage();
        } catch (Exception e) {
            log.error("mcp[{}] call tool {} failed", config.getName(), toolName, e);
            return "mcp[" + config.getName() + "] call failed: " + e.getMessage();
        }
    }

    /**
     * MCP content 数组 -> 单一字符串：text 直出、image/resource 转成占位标记；模型看得懂就行，避免上下文炸
     */
    private String flattenContent(JsonNode content) {
        if (content == null || !content.isArray() || content.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode item : content) {
            String type = item.path("type").asText("");
            switch (type) {
                case "text" -> {
                    if (sb.length() > 0) {
                        sb.append("\n");
                    }
                    sb.append(item.path("text").asText(""));
                }
                case "image" -> {
                    if (sb.length() > 0) {
                        sb.append("\n");
                    }
                    sb.append("[image ").append(item.path("mimeType").asText("image/*"))
                            .append(" base64 ").append(item.path("data").asText("").length()).append("B]");
                }
                case "audio" -> {
                    if (sb.length() > 0) {
                        sb.append("\n");
                    }
                    sb.append("[audio ").append(item.path("mimeType").asText("audio/*")).append("]");
                }
                case "resource" -> {
                    if (sb.length() > 0) {
                        sb.append("\n");
                    }
                    JsonNode r = item.path("resource");
                    sb.append("[resource ").append(r.path("uri").asText("?")).append("]");
                    String embedded = r.path("text").asText("");
                    if (!embedded.isEmpty()) {
                        sb.append("\n").append(embedded);
                    }
                }
                default -> {
                    if (sb.length() > 0) {
                        sb.append("\n");
                    }
                    sb.append("[").append(type.isEmpty() ? "unknown" : type).append("]");
                }
            }
        }
        return sb.toString();
    }

    /**
     * 发一条 request 并等待响应：把 CompletableFuture 挂到 transport 上，超时抛 TimeoutException
     */
    private JsonNode sendRequest(String method, JsonNode params, long timeoutSec)
            throws TimeoutException, InterruptedException {
        McpTransport t = this.transport;
        if (t == null || t.isClosed()) {
            throw new McpException(McpException.INTERNAL_ERROR, "mcp transport not available");
        }
        long id = t.nextId();
        CompletableFuture<JsonNode> fut = new CompletableFuture<>();
        // 先挂 pending 再发，防止响应比 future 注册更快到达
        t.registerPending(id, fut);
        try {
            t.send(JsonRpc.request(id, method, params));
        } catch (Exception e) {
            fut.completeExceptionally(e);
            throw new McpException(McpException.INTERNAL_ERROR, "send failed: " + e.getMessage(), null, e);
        }
        JsonNode resp;
        try {
            resp = fut.get(timeoutSec, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw e;
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable c = e.getCause();
            throw new McpException(McpException.INTERNAL_ERROR,
                    "transport failure: " + (c == null ? e.getMessage() : c.getMessage()), null, c);
        }
        JsonNode err = JsonRpc.errorOf(resp);
        if (err != null) {
            throw new McpException(err.path("code").asInt(McpException.INTERNAL_ERROR),
                    err.path("message").asText("unknown error"),
                    err.path("data").isMissingNode() ? null : err.path("data").toString());
        }
        return resp;
    }

    private void sendNotification(String method, JsonNode params) {
        McpTransport t = this.transport;
        if (t == null || t.isClosed()) {
            return;
        }
        try {
            t.send(JsonRpc.notification(method, params));
        } catch (Exception e) {
            log.debug("mcp[{}] send notification {} failed: {}", config.getName(), method, e.getMessage());
        }
    }

    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        McpTransport t = this.transport;
        if (t != null) {
            try {
                t.close();
            } catch (Exception ignore) {
            }
        }
    }

    @Override
    public void onNotification(String method, JsonNode params) {
        // MCP 常见通知：notifications/message（Server 日志）、notifications/progress、notifications/tools/list_changed
        switch (method) {
            case "notifications/message" -> log.info("mcp[{}:log] {} {}",
                    config.getName(),
                    params.path("level").asText("info"),
                    params.path("data").isMissingNode() ? "" : params.path("data").toString());
            case "notifications/tools/list_changed" -> log.info("mcp[{}] tools list changed, consider /mcp reload",
                    config.getName());
            case "notifications/progress" -> log.debug("mcp[{}] progress: {}", config.getName(), params);
            default -> log.debug("mcp[{}] notification {}: {}", config.getName(), method, params);
        }
    }

    @Override
    public void onServerRequest(long id, String method, JsonNode params) {
        log.info("mcp[{}] server->client request not supported: {} (id={})", config.getName(), method, id);
    }
}
