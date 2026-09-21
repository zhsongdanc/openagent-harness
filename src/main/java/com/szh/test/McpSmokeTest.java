package com.szh.test;

import com.szh.mcp.client.McpClient;
import com.szh.mcp.client.McpClientManager;
import com.szh.mcp.client.McpToolInfo;
import com.szh.mcp.config.McpServerConfig;
import com.szh.mcp.tool.McpTool;
import com.szh.tool.Tool;
import com.szh.tool.ToolContext;
import com.szh.utils.ConfigUtil;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;

/**
 * @author demussong
 * @describe MCP Client 冒烟测试：用一个内嵌的 Python echo Server 走完整 stdio + JSON-RPC 流程，
 * 覆盖 initialize 握手、tools/list 分页拉取、tools/call 参数与结果、错误工具 isError、close 幂等。
 * <p>
 * 不依赖任何真实 MCP Server / 网络 / node 环境，mac 自带 python3 即可跑；测试结束自动清理临时脚本。
 * 用法：{@code mvn compile && java -cp target/classes:... com.szh.test.McpSmokeTest}
 * @date 2026/9/22
 */
public class McpSmokeTest {

    /** 极简 Python MCP Server，覆盖 initialize / tools.list / tools.call 三个核心方法 */
    private static final String ECHO_SERVER_PY = """
            #!/usr/bin/env python3
            import sys, json

            def send(msg):
                sys.stdout.write(json.dumps(msg) + "\\n")
                sys.stdout.flush()

            TOOLS = [
                {
                    "name": "echo",
                    "description": "Echo back the input text with a prefix",
                    "inputSchema": {
                        "type": "object",
                        "properties": {"text": {"type": "string", "description": "text to echo"}},
                        "required": ["text"]
                    }
                },
                {
                    "name": "add",
                    "description": "Add two numbers and return the sum as text",
                    "inputSchema": {
                        "type": "object",
                        "properties": {"a": {"type": "number"}, "b": {"type": "number"}},
                        "required": ["a", "b"]
                    }
                }
            ]

            sys.stderr.write("echo-server started\\n")
            sys.stderr.flush()

            for line in sys.stdin:
                line = line.strip()
                if not line:
                    continue
                try:
                    msg = json.loads(line)
                except Exception as e:
                    sys.stderr.write("bad json: " + str(e) + "\\n")
                    continue
                mid = msg.get("id")
                method = msg.get("method", "")
                if method == "initialize":
                    send({"jsonrpc":"2.0","id":mid,"result":{
                        "protocolVersion": msg.get("params",{}).get("protocolVersion","2024-11-05"),
                        "capabilities": {"tools": {"listChanged": False}},
                        "serverInfo": {"name":"echo-server","version":"0.1.0"},
                        "instructions": "smoke test server"
                    }})
                elif method == "notifications/initialized":
                    pass
                elif method == "tools/list":
                    send({"jsonrpc":"2.0","id":mid,"result":{"tools":TOOLS}})
                elif method == "tools/call":
                    params = msg.get("params", {}) or {}
                    name = params.get("name")
                    args = params.get("arguments", {}) or {}
                    if name == "echo":
                        text = str(args.get("text", ""))
                        send({"jsonrpc":"2.0","id":mid,"result":{
                            "content":[{"type":"text","text":"echo:" + text}],
                            "isError": False
                        }})
                    elif name == "add":
                        try:
                            total = float(args.get("a", 0)) + float(args.get("b", 0))
                            body = str(int(total)) if total.is_integer() else str(total)
                            send({"jsonrpc":"2.0","id":mid,"result":{
                                "content":[{"type":"text","text":body}],
                                "isError": False
                            }})
                        except Exception as e:
                            send({"jsonrpc":"2.0","id":mid,"result":{
                                "content":[{"type":"text","text":"add failed: " + str(e)}],
                                "isError": True
                            }})
                    else:
                        send({"jsonrpc":"2.0","id":mid,"result":{
                            "content":[{"type":"text","text":"unknown tool: " + str(name)}],
                            "isError": True
                        }})
                elif method == "ping":
                    send({"jsonrpc":"2.0","id":mid,"result":{}})
                elif mid is not None:
                    send({"jsonrpc":"2.0","id":mid,"error":{"code":-32601,"message":"method not found: " + method}})
            sys.stderr.write("echo-server eof, exiting\\n")
            """;

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        Path script = Files.createTempFile("openagent-echo-mcp-", ".py");
        Files.writeString(script, ECHO_SERVER_PY, StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(script, Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE));
        } catch (UnsupportedOperationException ignore) {
            // 非 POSIX 文件系统忽略
        }
        System.out.println("echo-server script: " + script);

        McpClient client = null;
        try {
            McpServerConfig cfg = new McpServerConfig();
            cfg.setName("smoke");
            // 直接 python3 <script>，绕过 shebang 兼容性
            cfg.setCommand("python3");
            cfg.setArgs(List.of(script.toAbsolutePath().toString()));
            cfg.setConnectTimeoutSeconds(10);
            cfg.setRpcTimeoutSeconds(10);

            client = new McpClient(cfg, System.getProperty("user.dir"));
            client.connect();

            assertTrue("connect ok", client.isConnected());
            assertTrue("serverInfo present", client.getServerInfo() != null && client.getServerInfo().contains("echo-server"));
            assertTrue("protocol negotiated", client.getNegotiatedProtocolVersion() != null);

            List<McpToolInfo> tools = client.getTools();
            assertEquals("tools count", 2, tools.size());
            assertTrue("has echo tool", tools.stream().anyMatch(t -> "echo".equals(t.getName())));
            assertTrue("has add tool", tools.stream().anyMatch(t -> "add".equals(t.getName())));
            McpToolInfo echo = tools.stream().filter(t -> "echo".equals(t.getName())).findFirst().orElseThrow();
            assertTrue("echo schema is JSON object",
                    echo.getInputSchemaJson() != null && echo.getInputSchemaJson().contains("\"type\":\"object\""));

            // tools/call 正常路径
            String r1 = client.callTool("echo", "{\"text\":\"hello\"}");
            assertEquals("echo result", "echo:hello", r1);

            String r2 = client.callTool("add", "{\"a\":3,\"b\":4}");
            assertEquals("add result", "7", r2);

            // 空参数 / null 参数：走 client 的空对象兜底
            String r3 = client.callTool("echo", null);
            assertEquals("echo null args", "echo:", r3);

            // 工具业务错误：isError=true 会加前缀
            String r4 = client.callTool("not_exist", "{}");
            assertTrue("isError prefix", r4.startsWith("mcp tool error:"));

            // 非法 JSON 参数：client 层直接拒
            String r5 = client.callTool("echo", "not-json");
            assertTrue("bad json rejected", r5.contains("不是合法 JSON") || r5.contains("mcp tool error"));

            // 下一段验证 McpClientManager + mcp.json 配置加载的集成链路，与上面 client 直连测试独立
            client.close();
            client = null;
            testManagerEndToEnd(script);

            System.out.println();
            System.out.println("========================================");
            System.out.println(" McpSmokeTest: PASS=" + passed + " FAIL=" + failed);
            System.out.println("========================================");
            if (failed > 0) {
                System.exit(1);
            }
        } finally {
            if (client != null) {
                client.close();
                // 幂等：再次 close 不应抛异常
                client.close();
            }
            try {
                Files.deleteIfExists(script);
            } catch (Exception ignore) {
            }
        }
    }

    /**
     * 端到端验证 McpClientManager：写临时 mcp.json -> System.setProperty 覆盖路径 -> reload ->
     * 验证 client 数量 / tools 前缀 / 工具可直接 execute。
     */
    private static void testManagerEndToEnd(Path script) throws Exception {
        Path cfgFile = Files.createTempFile("openagent-mcp-smoke-", ".json");
        String json = """
                {
                  "mcpServers": {
                    "smoke": {
                      "command": "python3",
                      "args": ["%s"],
                      "connectTimeoutSeconds": 10,
                      "rpcTimeoutSeconds": 10
                    },
                    "missing": {
                      "command": "/definitely/not/exist",
                      "args": []
                    },
                    "off": {
                      "command": "python3",
                      "args": ["%s"],
                      "disabled": true
                    }
                  }
                }
                """.formatted(script.toAbsolutePath(), script.toAbsolutePath());
        Files.writeString(cfgFile, json, StandardCharsets.UTF_8);

        System.setProperty("mcp.config.file", cfgFile.toAbsolutePath().toString());
        ConfigUtil.clearCache();
        try {
            McpClientManager mgr = McpClientManager.get();
            mgr.reload();

            List<McpClient> clients = mgr.getClients();
            assertEquals("manager connected count", 1, clients.size());
            assertEquals("manager connected server", "smoke", clients.get(0).getServerName());
            assertTrue("missing server recorded as failure", mgr.getFailures().containsKey("missing"));
            assertTrue("disabled server not in failures", !mgr.getFailures().containsKey("off"));
            assertTrue("status text contains smoke", mgr.statusText().contains("smoke"));

            // McpTool 包装：code = server__tool，description 带 [MCP:server] 前缀，execute 能回传结果
            McpClient smoke = clients.get(0);
            McpToolInfo echo = smoke.getTools().stream().filter(t -> "echo".equals(t.getName())).findFirst().orElseThrow();
            Tool wrapped = new McpTool(smoke, echo);
            assertEquals("wrapped code", "smoke__echo", wrapped.getCode());
            assertTrue("wrapped description prefixed",
                    wrapped.getToolDefinition().getDescription().startsWith("[MCP:smoke]"));
            assertTrue("wrapped parameters schema",
                    wrapped.getToolDefinition().getParameters().contains("\"type\":\"object\""));
            String out = wrapped.execute(new ToolContext(null, null, null, "{\"text\":\"world\"}"));
            assertEquals("wrapped execute", "echo:world", out);
        } finally {
            System.clearProperty("mcp.config.file");
            ConfigUtil.clearCache();
            McpClientManager.get().shutdown();
            try {
                Files.deleteIfExists(cfgFile);
            } catch (Exception ignore) {
            }
        }
    }

    private static void assertTrue(String label, boolean cond) {
        if (cond) {
            passed++;
            System.out.println("[PASS] " + label);
        } else {
            failed++;
            System.out.println("[FAIL] " + label);
        }
    }

    private static void assertEquals(String label, Object expected, Object actual) {
        boolean eq = expected == null ? actual == null : expected.equals(actual);
        if (eq) {
            passed++;
            System.out.println("[PASS] " + label + " = " + actual);
        } else {
            failed++;
            System.out.println("[FAIL] " + label + " expected=" + expected + " actual=" + actual);
        }
    }
}
