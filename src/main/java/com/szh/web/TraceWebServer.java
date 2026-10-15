package com.szh.web;

import com.szh.event.Event;
import com.szh.store.StoreEnum;
import com.szh.utils.ConfigUtil;
import com.szh.utils.JsonUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * 本地 Trace 查看器（Web Viewer）服务端：一个只读的、零新增依赖的小 HTTP 服务。
 * <p>
 * 定位与边界：
 * <ul>
 *   <li><b>只读</b>：仅读取事件日志与上下文快照，从不写入、不参与 agent 运行、不需要任何 API Key；
 *       与主工程的耦合只有「读事件流 / 读快照 / 读工具结果文件」三件事。</li>
 *   <li><b>零依赖</b>：用 JDK 自带的 {@link HttpServer}（jdk.httpserver 模块，classpath 应用默认解析），
 *       前端是原生 JS 单页（无构建、无 CDN），对齐项目既有的「纯 JDK、无新增依赖」风格。</li>
 *   <li><b>只绑本机</b>：默认 {@code 127.0.0.1}，无鉴权——它就是本机开发调试工具，不要暴露到公网。</li>
 * </ul>
 * 启动方式：{@code bin/openagent-web [--port 8787] [--workspace /path/to/repo]}，
 * 或 {@code java -cp openagent.jar com.szh.web.TraceWebServer}。
 * <p>
 * 接口一览（全部 GET，返回 JSON；异常统一 500 + {@code {"error":...}}）：
 * <ul>
 *   <li>{@code GET /} 与 {@code /static/*}：单页前端资源；</li>
 *   <li>{@code GET /api/meta}：存储引擎 / 工作区 / 会话目录 / 快照目录等环境信息；</li>
 *   <li>{@code GET /api/sessions}：会话清单（含规模统计与最近用户输入）；</li>
 *   <li>{@code GET /api/sessions/{id}}：会话详情（turn → run → round → 步骤）；</li>
 *   <li>{@code GET /api/sessions/{id}/context?runId=&round=}：某轮送给模型的上下文（优先精确快照，缺失则回退重建）；</li>
 *   <li>{@code GET /api/sessions/{id}/events?offset=&limit=}：原始事件（调试用，含 payload 原文）；</li>
 *   <li>{@code GET /api/tool-result?session=&callId=&offset=&limit=}：工具落盘结果的完整正文（分页）。</li>
 * </ul>
 *
 * @author demussong
 * @date 2026/10/15
 */
@Slf4j
public class TraceWebServer {

    private static final String STATIC_ROOT = "/web/";

    private static final int DEFAULT_PORT = 8787;

    private static final String DEFAULT_HOST = "127.0.0.1";

    /**
     * 工具结果落盘目录（与 {@code ToolResultStore} 约定的同一条路径；
     * 这里只拼路径、不实例化 ToolResultStore，避免查看器「读一下」就顺手建目录）
     */
    private static final String TOOL_RESULT_DIR = "tool-results";

    private final HttpServer server;

    private final SessionCatalog catalog = new SessionCatalog();

    private final SessionViewBuilder viewBuilder = new SessionViewBuilder();

    private final ContextSnapshotReader snapshots = new ContextSnapshotReader();

    private final ContextReplayer replayer = new ContextReplayer();

    private final String workspace;

    private TraceWebServer(HttpServer server, String workspace) {
        this.server = server;
        this.workspace = workspace;
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> opts = parseArgs(args);
        // 工作区：优先命令行，其次 project.workspace（-D 或配置文件），最后 JVM 启动目录。
        // 必须在读取任何配置/装配 system prompt 之前落到系统属性，保证查看器与 agent 看到同一个工作区
        String workspace = opts.getOrDefault("workspace",
                ConfigUtil.get("project.workspace", System.getProperty("user.dir")));
        System.setProperty("project.workspace", workspace);
        String host = opts.getOrDefault("host", ConfigUtil.get("web.server.host", DEFAULT_HOST));
        int port = parsePort(opts.getOrDefault("port", ConfigUtil.get("web.server.port", String.valueOf(DEFAULT_PORT))));

        try {
            TraceWebServer web = start(host, port, workspace);
            log.info("Trace 查看器已启动: http://{}:{} (workspace={})", host, port, workspace);
            System.out.println("Trace 查看器已启动: http://" + host + ":" + port);
            System.out.println("工作区: " + workspace + "，存储引擎: " + web.catalog.storeType());
            System.out.println("按 Ctrl+C 退出");
        } catch (BindException e) {
            System.err.println("端口被占用: " + host + ":" + port + "，换一个端口：bin/openagent-web --port 8788");
            throw e;
        }
        // HttpServer 使用自带线程池，主线程不 join 会直接退出进程
        Thread.currentThread().join();
    }

    /**
     * 启动服务（独立 main 与将来可能的 REPL 集成共用同一入口）
     */
    public static TraceWebServer start(String host, int port, String workspace) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
        TraceWebServer web = new TraceWebServer(server, workspace);
        server.createContext("/", web.new RootHandler());
        // 守护线程池：不阻止 JVM 退出
        server.setExecutor(Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "trace-web");
            thread.setDaemon(true);
            return thread;
        }));
        server.start();
        return web;
    }

    public void stop() {
        server.stop(0);
    }

    public int port() {
        return server.getAddress().getPort();
    }

    // ==================== 路由 ====================

    private class RootHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            try {
                if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    sendJson(exchange, 405, error("仅支持 GET"));
                    return;
                }
                Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
                if (path.equals("/") || path.equals("/index.html")) {
                    sendResource(exchange, "index.html");
                    return;
                }
                if (path.startsWith("/static/")) {
                    sendResource(exchange, path.substring("/static/".length()));
                    return;
                }
                if (path.equals("/api/meta")) {
                    sendJson(exchange, 200, meta());
                    return;
                }
                if (path.equals("/api/sessions")) {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("sessions", catalog.list());
                    sendJson(exchange, 200, body);
                    return;
                }
                if (path.startsWith("/api/sessions/")) {
                    handleSession(exchange, path.substring("/api/sessions/".length()), query);
                    return;
                }
                if (path.equals("/api/tool-result")) {
                    handleToolResult(exchange, query);
                    return;
                }
                sendJson(exchange, 404, error("未知路径: " + path));
            } catch (Exception e) {
                log.warn("TraceWebServer: handle failed, path={}", path, e);
                sendJson(exchange, 500, error(e.getClass().getSimpleName() + ": " + e.getMessage()));
            }
        }
    }

    /**
     * /api/sessions/{id}、/api/sessions/{id}/context、/api/sessions/{id}/events
     */
    private void handleSession(HttpExchange exchange, String rest, Map<String, String> query) throws IOException {
        String[] parts = rest.split("/");
        String sessionId = decode(parts[0]);
        if (sessionId.isBlank() || !catalog.exists(sessionId)) {
            sendJson(exchange, 404, error("会话不存在: " + sessionId));
            return;
        }
        String action = parts.length > 1 ? parts[1] : "";
        switch (action) {
            case "" -> {
                List<Event> events = catalog.events(sessionId);
                Map<String, Object> view = viewBuilder.build(sessionId, events, snapshots);
                view.put("storeEngine", catalog.storeType().name());
                view.put("snapshotEnabled", snapshots.enabled());
                sendJson(exchange, 200, view);
            }
            case "context" -> sendJson(exchange, 200, context(sessionId, query));
            case "events" -> {
                List<Event> events = catalog.events(sessionId);
                int offset = intParam(query, "offset", 0);
                int limit = intParam(query, "limit", 200);
                sendJson(exchange, 200, rawEvents(sessionId, events, offset, limit));
            }
            default -> sendJson(exchange, 404, error("未知子路径: " + action));
        }
    }

    /**
     * 某轮送给模型的上下文：优先精确快照（{@code trace.context.dir} 侧车文件），缺失则回退为事件流重建
     */
    private Map<String, Object> context(String sessionId, Map<String, String> query) {
        String runId = query.get("runId");
        int round = intParam(query, "round", 0);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", sessionId);
        body.put("runId", runId);
        body.put("round", round);

        var snapshot = snapshots.read(sessionId, runId, round);
        if (snapshot != null) {
            body.put("source", "snapshot");
            body.put("note", "来自 model.call 前的上下文快照，逐字还原（含运行期装饰后的 system prompt）");
            body.put("messageCount", snapshot.messageCount());
            body.put("estimatedTokens", snapshot.estimatedTokens());
            body.put("tools", snapshot.tools());
            body.put("messages", snapshot.messages());
            return body;
        }
        var rebuilt = replayer.rebuild(catalog.events(sessionId), runId, round);
        if (rebuilt == null) {
            body.put("source", "none");
            body.put("note", snapshots.enabled()
                    ? "该轮没有快照，且事件流无法定位到该 run（历史事件可能已被回溯截断）"
                    : "会话未开启上下文快照（trace.context.enabled=false），且事件流无法定位该轮");
            body.put("messageCount", 0);
            body.put("estimatedTokens", 0);
            body.put("tools", List.of());
            body.put("messages", List.of());
            return body;
        }
        body.put("source", "rebuilt");
        body.put("note", "该轮无快照（能力上线前的历史会话或已关闭 trace.context.enabled），"
                + "此处由事件流按 AgentState 同口径重建：system prompt 按当前工作区重新装配，"
                + "运行期软装饰（记忆召回/规划契约/验证提示/护栏纠偏）无法还原");
        body.put("messageCount", rebuilt.messages().size());
        body.put("estimatedTokens", rebuilt.estimatedTokens());
        body.put("tools", List.of());
        body.put("messages", rebuilt.messages());
        return body;
    }

    /**
     * 原始事件（调试用）：payload 原文一并给出，便于确认查看器展示与落库是否一致
     */
    private Map<String, Object> rawEvents(String sessionId, List<Event> events, int offset, int limit) {
        int from = Math.max(0, Math.min(offset, events.size()));
        int to = Math.max(from, Math.min(from + limit, events.size()));
        List<Map<String, Object>> items = new ArrayList<>();
        for (Event event : events.subList(from, to)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", event.getId());
            item.put("type", event.getType().name());
            item.put("time", event.getTimestamp());
            item.put("runId", event.getRunId());
            item.put("turnId", event.getTurnId());
            item.put("round", event.getRound());
            item.put("payload", JsonUtil.toJson(event.payloadData()));
            items.add(item);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", sessionId);
        body.put("total", events.size());
        body.put("offset", from);
        body.put("limit", limit);
        body.put("events", items);
        return body;
    }

    /**
     * 工具落盘结果正文（分页）：让超长工具输出不必挤进会话详情响应，点开才拉取
     */
    private void handleToolResult(HttpExchange exchange, Map<String, String> query) throws IOException {
        String sessionId = query.get("session");
        String callId = query.get("callId");
        if (sessionId == null || callId == null) {
            sendJson(exchange, 400, error("缺少 session 或 callId 参数"));
            return;
        }
        Path file = toolResultFile(sessionId, callId);
        if (!Files.isRegularFile(file)) {
            sendJson(exchange, 404, error("工具结果文件不存在（会话可能来自其它工作区）: " + file));
            return;
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        int offset = intParam(query, "offset", 0);
        int limit = intParam(query, "limit", 2000);
        int from = Math.max(0, Math.min(offset, lines.size()));
        int to = Math.max(from, Math.min(from + limit, lines.size()));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("callId", callId);
        body.put("totalLines", lines.size());
        body.put("offset", from);
        body.put("limit", limit);
        body.put("content", String.join("\n", lines.subList(from, to)));
        sendJson(exchange, 200, body);
    }

    private Path toolResultFile(String sessionId, String callId) {
        return Path.of(workspace, ".agent-data", TOOL_RESULT_DIR, SessionCatalog.sanitize(sessionId))
                .resolve(SessionCatalog.sanitize(callId) + ".txt");
    }

    private Map<String, Object> meta() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("storeEngine", catalog.storeType().name());
        meta.put("workspace", workspace);
        meta.put("sessionDir", catalog.sessionDir() == null ? null : catalog.sessionDir().toString());
        meta.put("snapshotDir", snapshots.rootDir().toString());
        meta.put("snapshotEnabled", snapshots.enabled());
        meta.put("toolResultDir", Path.of(workspace, ".agent-data", TOOL_RESULT_DIR).toString());
        meta.put("memoryWarning", catalog.storeType() == StoreEnum.MEMORY
                ? "MEMORY 引擎：事件只在写入它的进程内存里，本查看器只能看到自己进程内的事件"
                : null);
        meta.put("sessionCount", catalog.list().size());
        return meta;
    }

    // ==================== HTTP 工具方法 ====================

    /**
     * 从 classpath {@code /web/} 下取静态资源（前端单页），拒绝路径穿越
     */
    private void sendResource(HttpExchange exchange, String name) throws IOException {
        if (name.contains("..") || name.startsWith("/")) {
            sendJson(exchange, 400, error("非法资源路径"));
            return;
        }
        String resource = STATIC_ROOT + name;
        try (InputStream in = TraceWebServer.class.getResourceAsStream(resource)) {
            if (in == null) {
                sendJson(exchange, 404, error("资源不存在: " + name));
                return;
            }
            byte[] bytes = in.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", contentType(name));
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    private void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        String json = JsonUtil.toJson(body);
        byte[] bytes = (json == null ? "{}" : json).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        return body;
    }

    private static String contentType(String name) {
        String lower = name.toLowerCase();
        if (lower.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (lower.endsWith(".js")) {
            return "application/javascript; charset=utf-8";
        }
        if (lower.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (lower.endsWith(".svg")) {
            return "image/svg+xml";
        }
        return "application/octet-stream";
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> query = new HashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return query;
        }
        for (String pair : rawQuery.split("&")) {
            int idx = pair.indexOf('=');
            if (idx <= 0) {
                continue;
            }
            query.put(decode(pair.substring(0, idx)), decode(pair.substring(idx + 1)));
        }
        return query;
    }

    private static String decode(String value) {
        if (value == null) {
            return "";
        }
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            return value;
        }
    }

    private static int intParam(Map<String, String> query, String key, int defaultValue) {
        String raw = query.get(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static int parsePort(String raw) {
        try {
            int port = Integer.parseInt(raw.trim());
            return port > 0 && port < 65536 ? port : DEFAULT_PORT;
        } catch (RuntimeException e) {
            return DEFAULT_PORT;
        }
    }

    /**
     * 极简参数解析：{@code --port 8787 --host 127.0.0.1 --workspace /path}
     */
    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> opts = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) {
                continue;
            }
            String key = arg.substring(2);
            int eq = key.indexOf('=');
            if (eq > 0) {
                opts.put(key.substring(0, eq), key.substring(eq + 1));
            } else if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                opts.put(key, args[++i]);
            } else {
                opts.put(key, "true");
            }
        }
        return opts;
    }
}
