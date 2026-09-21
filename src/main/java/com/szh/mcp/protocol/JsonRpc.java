package com.szh.mcp.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.szh.utils.JsonUtil;

/**
 * @author demussong
 * @describe JSON-RPC 2.0 消息构造/判别工具（MCP 协议底座）。
 * <p>
 * MCP 规范约定：所有传输上跑的都是标准 JSON-RPC 2.0 报文，含 request（有 id、有 method）、
 * response（有 id、有 result 或 error）、notification（无 id、有 method）三种形态；
 * 本类只负责构造与判别，不做 IO 与状态机（那些交给 {@code McpClient}/{@code StdioTransport}）。
 * @date 2026/9/22
 */
public final class JsonRpc {

    public static final String VERSION = "2.0";

    private JsonRpc() {
    }

    /**
     * 构造一条 request（有 id 期望响应）
     */
    public static ObjectNode request(long id, String method, JsonNode params) {
        ObjectNode node = JsonUtil.getMapper().createObjectNode();
        node.put("jsonrpc", VERSION);
        node.put("id", id);
        node.put("method", method);
        if (params != null) {
            node.set("params", params);
        }
        return node;
    }

    /**
     * 构造一条 notification（无 id、不期望响应），MCP 用它做 initialized / cancelled / progress 等信号
     */
    public static ObjectNode notification(String method, JsonNode params) {
        ObjectNode node = JsonUtil.getMapper().createObjectNode();
        node.put("jsonrpc", VERSION);
        node.put("method", method);
        if (params != null) {
            node.set("params", params);
        }
        return node;
    }

    /**
     * 判别是否为 response：有 id 且携带 result 或 error
     */
    public static boolean isResponse(JsonNode msg) {
        return msg != null && msg.hasNonNull("id") && (msg.has("result") || msg.has("error"));
    }

    /**
     * 判别是否为 notification：无 id、有 method
     */
    public static boolean isNotification(JsonNode msg) {
        return msg != null && !msg.hasNonNull("id") && msg.hasNonNull("method");
    }

    /**
     * 判别是否为服务端反向 request：有 id、有 method（MCP sampling / roots 场景，当前不处理但要能识别）
     */
    public static boolean isServerRequest(JsonNode msg) {
        return msg != null && msg.hasNonNull("id") && msg.hasNonNull("method");
    }

    /**
     * 从 response 中拆出 error 节点，无错误返回 null
     */
    public static JsonNode errorOf(JsonNode response) {
        if (response == null) {
            return null;
        }
        JsonNode err = response.get("error");
        return err == null || err.isNull() ? null : err;
    }
}
