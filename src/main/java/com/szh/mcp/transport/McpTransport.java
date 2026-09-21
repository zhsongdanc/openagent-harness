package com.szh.mcp.transport;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

/**
 * @author demussong
 * @describe MCP 传输层抽象：把「JSON-RPC 消息收发」从具体通道里剥离，方便未来扩展 HTTP+SSE / Streamable HTTP。
 * <p>
 * 契约：
 * <ol>
 *   <li>{@link #send(JsonNode)} 只负责把一条完整消息写出去（NDJSON：一行一条），不区分 request/notification；</li>
 *   <li>{@link #registerPending(long, CompletableFuture)} 由上层在发送 request 前挂 future，
 *       传输层收到匹配 id 的 response 时 {@code complete(response)}；</li>
 *   <li>服务端主动发的 notification / server-request 走 {@link Listener}，避免与响应路由混一起；</li>
 *   <li>{@link #close()} 必须幂等；关闭时把所有未完成的 pending 以异常收尾，防止调用方永久阻塞。</li>
 * </ol>
 * @date 2026/9/22
 */
public interface McpTransport extends AutoCloseable {

    /**
     * 分配一个单调递增的 request id
     */
    long nextId();

    /**
     * 注册一个 pending future，收到对应 id 的 response 时完成
     */
    void registerPending(long id, CompletableFuture<JsonNode> future);

    /**
     * 发送一条消息（request / notification 都用这个）
     */
    void send(JsonNode message) throws IOException;

    /**
     * 传输是否已关闭
     */
    boolean isClosed();

    /**
     * 关闭传输，释放子进程或 HTTP 连接
     */
    @Override
    void close();

    /**
     * 服务端主动消息回调（notification / server-request），由 McpClient 决定处理策略
     */
    interface Listener {
        /**
         * 收到 notification（无 id）
         */
        default void onNotification(String method, JsonNode params) {
        }

        /**
         * 收到 server -> client 的反向 request（如 sampling/createMessage、roots/list），当前实现不回，仅记录
         */
        default void onServerRequest(long id, String method, JsonNode params) {
        }
    }
}
