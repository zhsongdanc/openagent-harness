package com.szh.tool.store;

import com.szh.utils.CommonUtils;
import com.szh.utils.ConfigUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工具结果文件存储：将每次工具执行的完整输出写入文件，
 * 模型上下文中只保留引用（resultId + 摘要），模型通过 read_tool_result 工具按 offset/limit 分页查阅。
 * <p>
 * 存储路径：{workspace}/.agent-data/tool-results/{sessionId}/
 * 文件命名：{resultId}.txt（resultId 取自工具调用的 callId，全局唯一且可回溯）
 * <p>
 * 仅当输出超过阈值（行数或字符数）时才落盘并回传带头尾预览的引用存根，小输出直接内联进上下文，
 * 避免为每个工具调用都多绕一次 read_tool_result。落盘阈值与 StepCompactor 的 L0 截断阈值共用同一配置
 * tool.result.max.chars，保证“会被压缩截断的输出必有文件备份”，杜绝静默数据丢失。
 *
 * @author demussong
 * @date 2026/9/17
 */
@Slf4j
public class ToolResultStore {

    /**
     * 单条工具结果在上下文中的最大字符数：超过即落盘，只回传带 result_id 的存根。
     * 与 StepCompactor 的 L0 截断阈值共用同一配置，保证“会被截断的输出必有文件备份”，杜绝静默数据丢失。
     */
    private static final int MAX_INLINE_CHARS =
            ConfigUtil.getInt("tool.result.max.chars", 4000);
    /** 单条工具结果的最大内联行数：超过即落盘（应对“行数多但每行短”的输出） */
    private static final int MAX_INLINE_LINES =
            ConfigUtil.getInt("tool.result.max.lines", 50);
    /** 落盘存根中头尾预览的最大字符数，让模型无需额外一跳即可看到关键信息（命令回显/报错/结论） */
    private static final int PREVIEW_MAX_CHARS =
            ConfigUtil.getInt("tool.result.preview.chars", 1500);

    /**
     * 落盘存根的标识前缀：上下文中的工具结果若包含此标记，说明完整正文已落盘、可通过 result_id 回读。
     * 压缩器据此区分“已落盘（可清空只留把手）”与“内联正文（无备份，不可清空）”。
     */
    public static final String SPILL_MARKER = "[结果已落盘]";

    /** 从存根中提取 result_id（sanitize 后仅含 [A-Za-z0-9_-]，正则可靠） */
    private static final Pattern RESULT_ID_PATTERN = Pattern.compile("result_id=([A-Za-z0-9_-]+)");

    private final Path storeDir;

    public ToolResultStore(String workspace, String sessionId) {
        this.storeDir = Path.of(workspace, ".agent-data", "tool-results", sessionId);
        try {
            Files.createDirectories(storeDir);
        } catch (IOException e) {
            log.error("ToolResultStore: failed to create store directory: {}", storeDir, e);
        }
    }

    /**
     * 处理工具输出，决定内联还是落盘：
     * 输出较小（行数与字符数均未达阈值）时直接返回原文内联进上下文；
     * 输出较大时写入文件并返回引用存根，模型再用 read_tool_result 分页查阅。
     * <p>
     * 注意：read_tool_result 自身的输出应由调用方豁免，不经过此方法，否则会无限套娃。
     *
     * @param resultId 结果标识，通常传工具调用的 callId，保证全局唯一且可回溯
     * @param toolCode 工具代码（如 grep、cat）
     * @param output   工具完整输出
     * @return 内联原文，或引用存根字符串
     */
    public String presentResult(String resultId, String toolCode, String output) {
        if (!shouldSpill(output)) {
            return output != null ? output : "";
        }
        String id = store(resultId, toolCode, output);
        int lineCount = output.split("\n", -1).length;
        int charCount = output.length();
        // 头尾预览（参考主流 harness）；result_id 置顶，即使后续被 L0 头尾截断，把手也保留在头部不丢失
        String preview = CommonUtils.truncateHeadTail(output, PREVIEW_MAX_CHARS,
                omitted -> "\n... [省略 " + omitted + " 字符，完整内容用 read_tool_result(result_id=" + id
                        + ", offset, limit) 分页查阅] ...\n");
        return SPILL_MARKER + " 工具 " + toolCode + " 输出较大（" + lineCount + " 行 / " + charCount + " 字符），"
                + "result_id=" + id + "，可用 read_tool_result 分页查阅完整内容。预览：\n" + preview;
    }

    /**
     * 判断输出是否需要落盘：行数或字符数任一超过阈值即落盘，否则内联。
     * 边界与 StepCompactor 一致（严格大于才落盘/截断），避免恰好等于阈值时既内联又被截断。
     */
    public boolean shouldSpill(String output) {
        if (output == null || output.isEmpty()) {
            return false;
        }
        if (output.length() > MAX_INLINE_CHARS) {
            return true;
        }
        return output.split("\n", -1).length > MAX_INLINE_LINES;
    }

    /**
     * 存储工具输出到文件，返回规范化后的 resultId（即实际文件名，不含扩展名）。
     *
     * @param resultId 结果标识，通常传工具调用的 callId
     * @param toolCode 工具代码（如 grep、cat）
     * @param output   工具完整输出
     * @return 规范化后的 resultId，后续用于分页读取
     */
    public String store(String resultId, String toolCode, String output) {
        String id = sanitize(resultId);
        Path file = storeDir.resolve(id + ".txt");
        try {
            Files.writeString(file, output != null ? output : "", StandardCharsets.UTF_8);
            int lineCount = output == null ? 0 : output.split("\n", -1).length;
            log.info("ToolResultStore: stored resultId={}, tool={}, lines={}, chars={}",
                    id, toolCode, lineCount, output == null ? 0 : output.length());
        } catch (IOException e) {
            log.error("ToolResultStore: failed to write result file: {}", file, e);
        }
        return id;
    }

    /**
     * 静态分页读取：不依赖实例计数器，直接从指定目录读取。
     * 供 ReadToolResultTool 在运行时通过 ToolContext 定位文件后调用。
     */
    public static Map<String, Object> readPageFrom(Path storeDir, String resultId, int offset, int limit) {
        Path file = storeDir.resolve(sanitize(resultId) + ".txt");
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

    /**
     * 规范化 resultId 为文件系统安全的文件名：仅保留字母、数字、下划线、连字符，
     * 其余字符（含 '.' 与路径分隔符）一律替换为 '_'，避免路径穿越；空值兜底为随机 UUID。
     */
    private static String sanitize(String resultId) {
        if (resultId == null || resultId.isBlank()) {
            return "result-" + CommonUtils.generateId();
        }
        String cleaned = resultId.trim().replaceAll("[^a-zA-Z0-9_-]", "_");
        return cleaned.isEmpty() ? "result-" + CommonUtils.generateId() : cleaned;
    }

    /**
     * 若一段工具结果内容是落盘存根（含 {@link #SPILL_MARKER}），则提取其 result_id；否则返回 null。
     * <p>
     * 压缩器用它判定“该结果是否有文件备份、可否安全清空正文只留把手”，避免误清无备份的内联正文。
     */
    public static String extractSpilledResultId(String content) {
        if (content == null || !content.contains(SPILL_MARKER)) {
            return null;
        }
        Matcher matcher = RESULT_ID_PATTERN.matcher(content);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * 构造“已落盘旧结果被清空”后的占位文本：丢弃占空间的正文/预览，仅保留可回读把手。
     * 仍带 {@link #SPILL_MARKER} 与 result_id，保证再次压缩时幂等且把手不丢。
     */
    public static String clearedPlaceholder(String resultId, String toolCode) {
        return SPILL_MARKER + " 工具 " + toolCode + " 的历史结果已清除以节省上下文，"
                + "result_id=" + resultId + "，如需完整内容用 read_tool_result 回读。";
    }

    public Path getStoreDir() {
        return storeDir;
    }
}
