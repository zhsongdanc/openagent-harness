package com.szh.mcp.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.szh.mcp.protocol.JsonRpc;
import com.szh.mcp.protocol.McpException;
import com.szh.utils.JsonUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author demussong
 * @describe MCP stdio 传输：把子进程 stdin/stdout 当成 NDJSON（每行一条 JSON-RPC 报文）管道，
 * 也是 Claude Desktop / Codex / Cursor 的默认传输方式，覆盖 90%+ 的开源 MCP Server。
 * <p>
 * 关键设计：
 * <ol>
 *   <li><b>独立读线程 + 独立 stderr 线程</b>：stdout 用来跑协议消息，stderr 用来落 Server 日志；
 *       两者都必须异步读干净，否则子进程缓冲区打满会直接卡死（很多 npm-based Server 会把大量 debug 打到 stderr）。</li>
 *   <li><b>pending 表按 id 路由</b>：{@link #registerPending(long, CompletableFuture)} 先挂后发，
 *       避免响应比 future 注册更快到达造成漏消息。</li>
 *   <li><b>EOF/崩溃兜底</b>：读线程退出时把所有 pending 用 IOException 完成，调用方不会永久阻塞。</li>
 *   <li><b>写侧同步</b>：多线程并发调用 tools/call 时，写 stdin 用同一把锁保证一行不被截断。</li>
 *   <li><b>关闭策略</b>：先 close stdin 让 Server 感知输入 EOF 自然退出，超时后 destroy，再超时 destroyForcibly。</li>
 * </ol>
 * @date 2026/9/22
 */
@Slf4j
public class StdioTransport implements McpTransport {

    /** 关闭时等待子进程自然退出的秒数，超过就 destroy */
    private static final long GRACEFUL_CLOSE_SECONDS = 2;

    private final String serverName;
    private final Process process;
    private final BufferedWriter stdin;
    private final Thread readerThread;
    private final Thread stderrThread;
    private final Listener listener;
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final AtomicLong idGen = new AtomicLong(1);
    private final Object writeLock = new Object();
    private volatile boolean closed = false;

    public StdioTransport(String serverName,
                          List<String> command,
                          Map<String, String> env,
                          String workdir,
                          Listener listener) throws IOException {
        this.serverName = serverName;
        this.listener = listener;

        ProcessBuilder pb = new ProcessBuilder(command);
        // stdout/stderr 分开：stdout 走协议，stderr 走日志，绝不 redirectErrorStream(true)
        pb.redirectErrorStream(false);
        if (env != null && !env.isEmpty()) {
            pb.environment().putAll(env);
        }
        if (workdir != null && !workdir.isBlank()) {
            File dir = new File(workdir);
            if (dir.isDirectory()) {
                pb.directory(dir);
            }
        }
        log.info("mcp[{}] spawn: {}", serverName, String.join(" ", command));
        this.process = pb.start();
        this.stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));

        this.readerThread = new Thread(this::readLoop, "mcp-stdio-reader-" + serverName);
        this.readerThread.setDaemon(true);
        this.readerThread.start();

        this.stderrThread = new Thread(this::drainStderr, "mcp-stdio-stderr-" + serverName);
        this.stderrThread.setDaemon(true);
        this.stderrThread.start();
    }

    @Override
    public long nextId() {
        return idGen.getAndIncrement();
    }

    @Override
    public void registerPending(long id, CompletableFuture<JsonNode> future) {
        pending.put(id, future);
    }

    @Override
    public void send(JsonNode message) throws IOException {
        if (closed) {
            throw new IOException("mcp[" + serverName + "] transport closed");
        }
        String line = JsonUtil.getMapper().writeValueAsString(message);
        synchronized (writeLock) {
            // NDJSON：一行一条 + \n 结束；BufferedWriter 攒够或 flush 时一次写入，减少子进程读端碎片
            stdin.write(line);
            stdin.write("\n");
            stdin.flush();
        }
        if (log.isDebugEnabled()) {
            log.debug("mcp[{}] --> {}", serverName, line);
        }
    }

    @Override
    public boolean isClosed() {
        return closed || !process.isAlive();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        // 先关 stdin，很多 Server 收到 EOF 会主动退出
        try {
            stdin.close();
        } catch (Exception ignore) {
        }
        try {
            if (!process.waitFor(GRACEFUL_CLOSE_SECONDS, TimeUnit.SECONDS)) {
                process.destroy();
                if (!process.waitFor(GRACEFUL_CLOSE_SECONDS, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        // 兜底：唤醒所有仍在等的 pending
        IOException cause = new IOException("mcp[" + serverName + "] transport closed");
        pending.values().forEach(f -> f.completeExceptionally(cause));
        pending.clear();
        log.info("mcp[{}] transport closed", serverName);
    }

    /**
     * stdout 读循环：一行一条 JSON-RPC 消息，按 id 路由到 pending 或走 listener
     */
    private void readLoop() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while (!closed && (line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode msg;
                try {
                    msg = JsonUtil.getMapper().readTree(line);
                } catch (Exception e) {
                    // Server 可能不小心把日志打到 stdout，跳过而不中断整个流
                    log.warn("mcp[{}] skip non-json stdout line: {}", serverName, truncate(line));
                    continue;
                }
                dispatch(msg);
            }
        } catch (IOException e) {
            if (!closed) {
                log.warn("mcp[{}] stdout read failed: {}", serverName, e.getMessage());
            }
        } finally {
            // 走到这里说明子进程 stdout EOF 或异常关闭，把所有 pending 都失败掉
            IOException cause = new IOException("mcp[" + serverName + "] server closed stdout");
            pending.values().forEach(f -> f.completeExceptionally(cause));
            pending.clear();
            if (!closed) {
                log.warn("mcp[{}] server exited unexpectedly, exitCode={}",
                        serverName, process.isAlive() ? "still-alive" : process.exitValue());
                // 主动触发 close 释放资源；close 内部幂等
                close();
            }
        }
    }

    private void dispatch(JsonNode msg) {
        if (log.isDebugEnabled()) {
            log.debug("mcp[{}] <-- {}", serverName, truncate(msg.toString()));
        }
        JsonNode idNode = msg.get("id");
        if (JsonRpc.isResponse(msg)) {
            long id = idNode.asLong();
            CompletableFuture<JsonNode> fut = pending.remove(id);
            if (fut != null) {
                fut.complete(msg);
            } else {
                log.debug("mcp[{}] drop response for unknown id={}", serverName, id);
            }
            return;
        }
        if (JsonRpc.isServerRequest(msg)) {
            // sampling/createMessage、roots/list 等：当前不做反向能力，直接回 method_not_found 让 Server 别卡等
            long id = idNode.asLong();
            String method = msg.path("method").asText("");
            if (listener != null) {
                listener.onServerRequest(id, method, msg.path("params"));
            }
            replyMethodNotFound(id, method);
            return;
        }
        if (JsonRpc.isNotification(msg)) {
            if (listener != null) {
                listener.onNotification(msg.path("method").asText(""), msg.path("params"));
            }
        }
    }

    /**
     * 对未实现的反向 request 显式回 method_not_found，避免 Server 无限等待；忽略写失败（进程可能已经在关）
     */
    private void replyMethodNotFound(long id, String method) {
        try {
            var err = JsonUtil.getMapper().createObjectNode();
            err.put("jsonrpc", JsonRpc.VERSION);
            err.put("id", id);
            var e = err.putObject("error");
            e.put("code", McpException.METHOD_NOT_FOUND);
            e.put("message", "client does not support server request: " + method);
            send(err);
        } catch (Exception ignore) {
        }
    }

    /**
     * stderr 逐行读到日志：不读会把 pipe 缓冲区打满导致子进程 write 阻塞
     */
    private void drainStderr() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                log.info("mcp[{}:stderr] {}", serverName, line);
            }
        } catch (IOException e) {
            if (!closed) {
                log.debug("mcp[{}] stderr read failed: {}", serverName, e.getMessage());
            }
        }
    }

    private static String truncate(String s) {
        return s.length() <= 800 ? s : s.substring(0, 800) + "...(" + s.length() + " chars)";
    }
}
