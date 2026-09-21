package com.szh.mcp.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.szh.utils.ConfigUtil;
import com.szh.utils.JsonUtil;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @author demussong
 * @describe MCP 配置文件加载器：读取 {@code ~/.openagent/mcp.json}（可被 {@code mcp.config.file} 覆盖），
 * 解析成 {@link McpServerConfig} 映射。
 * <p>
 * 文件格式对齐 Claude Desktop / Cursor：
 * <pre>
 * {
 *   "mcpServers": {
 *     "filesystem": {
 *       "command": "npx",
 *       "args": ["-y", "@modelcontextprotocol/server-filesystem", "/path"],
 *       "env": {"SOME_TOKEN": "xxx"},
 *       "disabled": false,
 *       "sandbox": false,
 *       "connectTimeoutSeconds": 15,
 *       "rpcTimeoutSeconds": 60
 *     }
 *   }
 * }
 * </pre>
 * 关键处理：
 * <ol>
 *   <li>文件缺失时返回空 map，仅打日志——不影响主流程；</li>
 *   <li>命令与参数支持 {@code ~} 展开为用户 home（{@code nvm} 装的 node 常在 {@code ~/...} 下）；</li>
 *   <li>env 值也支持 {@code ~} 展开以及 {@code ${VAR}} 环境变量替换（便于共享配置文件不写死 secret）；</li>
 *   <li>单个 Server 配置解析失败不影响其它 Server，尽最大努力装配。</li>
 * </ol>
 * @date 2026/9/22
 */
@Slf4j
public final class McpConfigLoader {

    /** 默认配置文件路径（用户级） */
    public static final String DEFAULT_PATH = "~/.openagent/mcp.json";

    private McpConfigLoader() {
    }

    /**
     * 加载 MCP 配置；未启用 / 文件不存在 / 解析失败时返回空 map
     */
    public static Map<String, McpServerConfig> load() {
        if (!ConfigUtil.getBoolean("mcp.enabled", true)) {
            log.info("mcp disabled by config");
            return Map.of();
        }
        String rawPath = ConfigUtil.get("mcp.config.file", DEFAULT_PATH);
        Path path = Path.of(expandHome(rawPath));
        if (!Files.exists(path)) {
            log.info("mcp config file not found: {} (create one to enable MCP servers)", path);
            return Map.of();
        }
        try {
            JsonNode root = JsonUtil.getMapper().readTree(path.toFile());
            JsonNode servers = root.path("mcpServers");
            if (!servers.isObject() || servers.isEmpty()) {
                log.info("mcp config {} has no mcpServers entries", path);
                return Map.of();
            }
            Map<String, McpServerConfig> result = new LinkedHashMap<>();
            // Jackson 2.19+ 推荐用 properties() 代替已过时的 fields()
            for (Map.Entry<String, JsonNode> entry : servers.properties()) {
                String name = entry.getKey();
                try {
                    McpServerConfig cfg = parseServer(name, entry.getValue());
                    if (cfg != null) {
                        result.put(name, cfg);
                    }
                } catch (Exception e) {
                    log.error("mcp config parse failed for server [{}]: {}", name, e.getMessage());
                }
            }
            log.info("mcp config loaded from {}: {} server(s)", path, result.size());
            return result;
        } catch (Exception e) {
            log.error("mcp config load failed: {}", path, e);
            return Map.of();
        }
    }

    private static McpServerConfig parseServer(String name, JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        McpServerConfig cfg = new McpServerConfig();
        cfg.setName(name);
        String command = expand(node.path("command").asText(null));
        if (command == null || command.isBlank()) {
            log.warn("mcp server [{}] missing 'command', skipped", name);
            return null;
        }
        cfg.setCommand(command);

        List<String> args = new ArrayList<>();
        JsonNode argsNode = node.path("args");
        if (argsNode.isArray()) {
            for (JsonNode a : argsNode) {
                String s = expand(a.asText(null));
                if (s != null) {
                    args.add(s);
                }
            }
        }
        cfg.setArgs(args);

        Map<String, String> env = new LinkedHashMap<>();
        JsonNode envNode = node.path("env");
        if (envNode.isObject()) {
            for (Map.Entry<String, JsonNode> e : envNode.properties()) {
                String v = expand(e.getValue().asText(null));
                if (v != null) {
                    env.put(e.getKey(), v);
                }
            }
        }
        cfg.setEnv(env);

        cfg.setCwd(expand(node.path("cwd").asText(null)));
        cfg.setDisabled(node.path("disabled").asBoolean(false));
        cfg.setSandbox(node.path("sandbox").asBoolean(false));
        cfg.setConnectTimeoutSeconds(node.path("connectTimeoutSeconds").asInt(0));
        cfg.setRpcTimeoutSeconds(node.path("rpcTimeoutSeconds").asInt(0));
        return cfg;
    }

    /**
     * 展开路径中的 ~ 与环境变量 ${VAR}；null 直接返回
     */
    static String expand(String s) {
        if (s == null) {
            return null;
        }
        String out = s;
        if (out.startsWith("~/")) {
            out = System.getProperty("user.home") + out.substring(1);
        } else if (out.equals("~")) {
            out = System.getProperty("user.home");
        }
        // ${VAR} 替换：让共享的 mcp.json 不必硬编码 secret
        if (out.contains("${")) {
            StringBuilder sb = new StringBuilder();
            int i = 0;
            while (i < out.length()) {
                int start = out.indexOf("${", i);
                if (start < 0) {
                    sb.append(out, i, out.length());
                    break;
                }
                int end = out.indexOf('}', start + 2);
                if (end < 0) {
                    sb.append(out, i, out.length());
                    break;
                }
                sb.append(out, i, start);
                String varName = out.substring(start + 2, end);
                String varVal = System.getenv(varName);
                sb.append(varVal == null ? "" : varVal);
                i = end + 1;
            }
            out = sb.toString();
        }
        return out;
    }

    private static String expandHome(String p) {
        if (p == null) {
            return DEFAULT_PATH;
        }
        String trimmed = p.trim();
        if (trimmed.startsWith("~/")) {
            return System.getProperty("user.home") + trimmed.substring(1);
        }
        if (trimmed.equals("~")) {
            return System.getProperty("user.home");
        }
        return trimmed;
    }
}
