package com.szh.tool.store;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 工具结果文件存储：将每次工具执行的完整输出写入文件，
 * 模型上下文中只保留引用（resultId + 摘要），模型通过 read_tool_result 工具按 offset/limit 分页查阅。
 * <p>
 * 存储路径：{workspace}/.agent-data/tool-results/{sessionId}/
 * 文件命名：{resultId}.txt（resultId 为 session 内自增序号）
 *
 * @author demussong
 * @date 2026/9/17
 */
@Slf4j
public class ToolResultStore {

    private final Path storeDir;
    private final AtomicInteger counter = new AtomicInteger(0);

    public ToolResultStore(String workspace, String sessionId) {
        this.storeDir = Path.of(workspace, ".agent-data", "tool-results", sessionId);
        try {
            Files.createDirectories(storeDir);
        } catch (IOException e) {
            log.error("ToolResultStore: failed to create store directory: {}", storeDir, e);
        }
    }

    /**
     * 存储工具输出到文件，返回分配的 resultId。
     *
     * @param toolCode   工具代码（如 grep、cat）
     * @param output     工具完整输出
     * @return resultId  后续用于分页读取的标识
     */
    public String store(String toolCode, String output) {
        String resultId = String.valueOf(counter.incrementAndGet());
        Path file = storeDir.resolve(resultId + ".txt");
        try {
            Files.writeString(file, output != null ? output : "", StandardCharsets.UTF_8);
            int lineCount = output == null ? 0 : output.split("\n", -1).length;
            log.info("ToolResultStore: stored resultId={}, tool={}, lines={}, chars={}",
                    resultId, toolCode, lineCount, output == null ? 0 : output.length());
        } catch (IOException e) {
            log.error("ToolResultStore: failed to write result file: {}", file, e);
        }
        return resultId;
    }

    /**
     * 分页读取工具结果。
     *
     * @param resultId 结果标识
     * @param offset   起始行号（0-based）
     * @param limit    最大返回行数
     * @return 包含 content、totalLines、offset、limit 的结果 Map；文件不存在时返回 null
     */
    public Map<String, Object> readPage(String resultId, int offset, int limit) {
        try {
            int id = Integer.parseInt(resultId);
            if (id < 1 || id > counter.get()) {
                return null;
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return readPageFrom(storeDir, resultId, offset, limit);
    }

    /**
     * 静态分页读取：不依赖实例计数器，直接从指定目录读取。
     * 供 ReadToolResultTool 在运行时通过 ToolContext 定位文件后调用。
     */
    public static Map<String, Object> readPageFrom(Path storeDir, String resultId, int offset, int limit) {
        Path file = storeDir.resolve(resultId + ".txt");
        if (!Files.exists(file)) {
            return null;
        }
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            int totalLines = lines.size();
            int from = Math.min(offset, totalLines);
            int to = Math.min(offset + limit, totalLines);

            StringBuilder content = new StringBuilder();
            for (int i = from; i < to; i++) {
                if (i > from) {
                    content.append("\n");
                }
                content.append(lines.get(i));
            }

            Map<String, Object> result = new HashMap<>();
            result.put("content", content.toString());
            result.put("totalLines", totalLines);
            result.put("offset", offset);
            result.put("limit", limit);
            result.put("returnedLines", to - from);
            return result;
        } catch (IOException e) {
            log.error("ToolResultStore: failed to read result file: {}", file, e);
            return null;
        }
    }

    /**
     * 根据 workspace 和 sessionId 定位工具结果存储目录。
     */
    public static Path resolveStoreDir(String workspace, String sessionId) {
        return Path.of(workspace, ".agent-data", "tool-results", sessionId);
    }

    public Path getStoreDir() {
        return storeDir;
    }
}
