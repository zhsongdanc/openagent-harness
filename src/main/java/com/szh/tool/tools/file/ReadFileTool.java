package com.szh.tool.tools.file;

import com.fasterxml.jackson.databind.JsonNode;
import com.szh.tool.ToolContext;
import com.szh.tool.ToolDefinition;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.List;

/**
 * 读文件工具：按行读取文本文件，输出带行号（对齐主流 harness 的 Read 工具），
 * 支持 offset/limit 分页，避免大文件一次性挤爆上下文（超限输出仍会走落盘存根机制）。
 * <p>
 * 行号只是展示辅助，edit_file 的 old_text 匹配的是原始内容（不含行号前缀）。
 *
 * @author demussong
 * @date 2026/9/21
 */
@Slf4j
public class ReadFileTool extends FileToolSupport {

    public static final String CODE = "read_file";

    /**
     * 未传 limit 时单次最多返回的行数
     */
    private static final int DEFAULT_LIMIT = 2000;

    private static final ToolDefinition TOOL_DEFINITION = ToolDefinition.builder()
            .name(CODE)
            .code(CODE)
            .type("file")
            .description("读取文本文件内容，输出带行号（格式：行号→内容）。支持 offset/limit 分页读取大文件。"
                    + "path 支持工作区相对路径或绝对路径")
            .parameters("{\"type\":\"object\",\"properties\":{"
                    + "\"path\":{\"type\":\"string\",\"description\":\"文件路径，相对路径以工作区为基准\"},"
                    + "\"offset\":{\"type\":\"integer\",\"description\":\"起始行号（从 1 开始），默认 1\"},"
                    + "\"limit\":{\"type\":\"integer\",\"description\":\"最多读取的行数，默认 2000\"}},"
                    + "\"required\":[\"path\"]}")
            .build();

    @Override
    public String getCode() {
        return CODE;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return TOOL_DEFINITION;
    }

    @Override
    public String execute(ToolContext toolContext) {
        try {
            JsonNode args = parseArgs(toolContext.getArgs());
            Path file = resolvePath(text(args, "path"), toolContext);
            checkReadable(file, toolContext);

            List<String> lines = readLines(file);
            int total = lines.size();
            // offset 从 1 开始（行号口径），内部转 0 基
            int offset = Math.max(1, integer(args, "offset", 1));
            int limit = Math.max(1, integer(args, "limit", DEFAULT_LIMIT));
            int from = Math.min(offset - 1, total);
            int to = Math.min(from + limit, total);

            StringBuilder sb = new StringBuilder();
            for (int i = from; i < to; i++) {
                sb.append(i + 1).append("→").append(lines.get(i)).append("\n");
            }
            String header = "文件: " + file + "（共 " + total + " 行，本次返回 " + (to - from) + " 行，"
                    + "范围 [" + (from + 1) + ", " + to + "]）\n";
            if (to < total) {
                header += "提示: 文件未读完，可用 offset=" + (to + 1) + " 继续分页读取\n";
            }
            return header + sb;
        } catch (IllegalArgumentException e) {
            log.warn("read_file 参数错误, args={}, reason={}", toolContext.getArgs(), e.getMessage());
            return CODE + " 参数错误：" + e.getMessage();
        } catch (Exception e) {
            log.error("read_file 执行失败, args={}", toolContext.getArgs(), e);
            return CODE + " 执行失败：" + e.getMessage();
        }
    }
}
