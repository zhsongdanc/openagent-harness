package com.szh.mcp.protocol;

/**
 * @author demussong
 * @describe MCP 协议异常：JSON-RPC 层错误码 + 服务端 message + 可选 data，供上层区分「协议错」与「工具业务错」。
 * <p>
 * MCP 工具执行失败优先走 result.isError=true + content 返回文本，本异常主要覆盖协议级问题：
 * 方法不存在、参数不合法、服务端内部错、请求超时等。
 * @date 2026/9/22
 */
public class McpException extends RuntimeException {

    /** JSON-RPC 标准错误码：解析失败 */
    public static final int PARSE_ERROR = -32700;
    /** JSON-RPC 标准错误码：请求非法 */
    public static final int INVALID_REQUEST = -32600;
    /** JSON-RPC 标准错误码：方法不存在 */
    public static final int METHOD_NOT_FOUND = -32601;
    /** JSON-RPC 标准错误码：参数非法 */
    public static final int INVALID_PARAMS = -32602;
    /** JSON-RPC 标准错误码：服务端内部错 */
    public static final int INTERNAL_ERROR = -32603;

    private final int code;
    private final String data;

    public McpException(int code, String message) {
        this(code, message, null, null);
    }

    public McpException(int code, String message, String data) {
        this(code, message, data, null);
    }

    public McpException(int code, String message, String data, Throwable cause) {
        super("mcp error code=" + code + ", message=" + message + (data == null ? "" : ", data=" + data), cause);
        this.code = code;
        this.data = data;
    }

    public int getCode() {
        return code;
    }

    public String getData() {
        return data;
    }
}
