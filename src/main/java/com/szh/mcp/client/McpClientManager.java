package com.szh.mcp.client;

import com.szh.mcp.config.McpConfigLoader;
import com.szh.mcp.config.McpServerConfig;
import com.szh.utils.ConfigUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * @author demussong
 * @describe MCP 客户端管理器（进程内单例）：负责按配置启动全部 Server、缓存 Client、聚合工具列表、
 * 处理 reload 与 JVM 关闭时的清理。
 * <p>
 * 设计要点：
 * <ol>
 *   <li><b>懒加载 + 幂等 init</b>：{@link #get()} 时不启动 Server，第一次调 {@link #ensureInit()} 才拉起；
 *       避免仅想读配置或跑单元测试的场景误启子进程；</li>
 *   <li><b>并发启动</b>：Server 数量可能多（npx 拉包慢），串行启动会拖长冷启动，用固定线程池并发；
 *       单 Server 失败不影响其它 Server，仅记录到 {@link #failures}；</li>
 *   <li><b>JVM shutdown hook</b>：确保 CLI/REPL 退出时子进程不残留，避免用户看到一堆僵尸 node 进程；</li>
 *   <li><b>命名空间隔离</b>：每个 Server 的工具 code 加 {@code {server}__} 前缀，与内置工具、
 *       其它 Server 的工具都不冲突（对齐 Claude Code 的做法）；</li>
 *   <li><b>reload</b>：关闭全部再 init，用于用户在会话中改完 {@code mcp.json} 想立刻生效。</li>
 * </ol>
 * @date 2026/9/22
 */
@Slf4j
public class McpClientManager {

    private static final McpClientManager INSTANCE = new McpClientManager();

    /** 启动阶段的并发度上限，避免同时拉起太多子进程把 CPU/网络打爆 */
    private static final int STARTUP_PARALLELISM = 4;

    private final Map<String, McpClient> clients = new ConcurrentHashMap<>();
    private final Map<String, String> failures = new ConcurrentHashMap<>();
    private final Map<String, McpServerConfig> configs = new ConcurrentHashMap<>();
    private volatile boolean initialized;
    private volatile Thread shutdownHook;

    private McpClientManager() {
    }

    public static McpClientManager get() {
        return INSTANCE;
    }

    /**
     * 幂等初始化：只有第一次调用会真正启动 Server，之后立即返回
     */
    public synchronized void ensureInit() {
        if (initialized) {
            return;
        }
        initialized = true;
        if (!ConfigUtil.getBoolean("mcp.enabled", true)) {
            log.info("mcp disabled by config (mcp.enabled=false)");
            return;
        }
        Map<String, McpServerConfig> loaded = McpConfigLoader.load();
        if (loaded.isEmpty()) {
            return;
        }
        configs.putAll(loaded);
        startAll(loaded);
        registerShutdownHook();
    }

    private void startAll(Map<String, McpServerConfig> loaded) {
        List<Map.Entry<String, McpServerConfig>> toStart = new ArrayList<>();
        for (Map.Entry<String, McpServerConfig> e : loaded.entrySet()) {
            if (e.getValue().isDisabled()) {
                log.info("mcp[{}] disabled by config, skipped", e.getKey());
                continue;
            }
            toStart.add(e);
        }
        if (toStart.isEmpty()) {
            return;
        }
        int parallelism = Math.min(toStart.size(), STARTUP_PARALLELISM);
        ExecutorService pool = Executors.newFixedThreadPool(parallelism, r -> {
            Thread t = new Thread(r, "mcp-startup");
            t.setDaemon(true);
            return t;
        });
        String workspace = ConfigUtil.get("project.workspace", System.getProperty("user.dir"));
        try {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (Map.Entry<String, McpServerConfig> e : toStart) {
                futures.add(pool.submit(() -> startOne(e.getKey(), e.getValue(), workspace)));
            }
            // 等待所有启动任务结束（每个任务内部有 connectTimeout 兜底，不会永远阻塞）
            for (java.util.concurrent.Future<?> f : futures) {
                try {
                    f.get();
                } catch (Exception ignore) {
                    // startOne 内部已 catch，走到这里只可能是 cancel/interrupt
                }
            }
        } finally {
            pool.shutdownNow();
            try {
                pool.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        log.info("mcp startup summary: connected={}, failed={}, skipped={}",
                clients.size(), failures.size(), loaded.size() - clients.size() - failures.size());
    }

    private void startOne(String name, McpServerConfig cfg, String workspace) {
        McpClient client = new McpClient(cfg, workspace);
        try {
            client.connect();
            clients.put(name, client);
        } catch (Exception e) {
            log.error("mcp[{}] startup failed: {}", name, e.getMessage());
            failures.put(name, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            try {
                client.close();
            } catch (Exception ignore) {
            }
        }
    }

    private void registerShutdownHook() {
        if (shutdownHook != null) {
            return;
        }
        shutdownHook = new Thread(this::shutdown, "mcp-shutdown-hook");
        try {
            Runtime.getRuntime().addShutdownHook(shutdownHook);
        } catch (IllegalStateException e) {
            // JVM 已经在关闭中，直接执行清理
            shutdown();
        }
    }

    /**
     * 关闭全部 Server 并清空缓存；下次 {@link #ensureInit()} 会重新启动
     */
    public synchronized void shutdown() {
        for (McpClient c : clients.values()) {
            try {
                c.close();
            } catch (Exception ignore) {
            }
        }
        clients.clear();
        failures.clear();
        configs.clear();
        initialized = false;
    }

    /**
     * reload：关闭 + 重新启动，用于用户改完 mcp.json 想立刻生效
     */
    public synchronized void reload() {
        log.info("mcp reload requested");
        shutdown();
        ensureInit();
    }

    /**
     * 获取全部已连接 Server 的工具信息（不包装成 {@code Tool}），供上层做展示或注册
     */
    public List<McpClient> getClients() {
        ensureInit();
        return List.copyOf(clients.values());
    }

    public Map<String, String> getFailures() {
        ensureInit();
        return Collections.unmodifiableMap(new LinkedHashMap<>(failures));
    }

    public Map<String, McpServerConfig> getConfigs() {
        ensureInit();
        return Collections.unmodifiableMap(new LinkedHashMap<>(configs));
    }

    /**
     * 状态汇总文本，供 {@code /mcp} 命令与启动 banner 使用
     */
    public String statusText() {
        ensureInit();
        StringBuilder sb = new StringBuilder();
        sb.append("MCP 状态：\n");
        sb.append("  已连接 Server: ").append(clients.size()).append("\n");
        sb.append("  启动失败 Server: ").append(failures.size()).append("\n");
        if (!clients.isEmpty()) {
            sb.append("  已连接：\n");
            clients.forEach((name, c) -> sb.append("    - ").append(name)
                    .append(" (").append(c.getServerInfo()).append(", protocol=")
                    .append(c.getNegotiatedProtocolVersion()).append(", tools=")
                    .append(c.getTools().size()).append(")\n"));
        }
        if (!failures.isEmpty()) {
            sb.append("  失败：\n");
            failures.forEach((name, err) -> sb.append("    - ").append(name).append(": ").append(err).append("\n"));
        }
        List<String> disabled = new ArrayList<>();
        configs.forEach((name, cfg) -> {
            if (cfg.isDisabled()) {
                disabled.add(name);
            }
        });
        if (!disabled.isEmpty()) {
            sb.append("  已禁用：").append(String.join(", ", disabled)).append("\n");
        }
        return sb.toString();
    }
}
