package com.szh.tool.tools.file;

import com.fasterxml.jackson.databind.JsonNode;
import com.szh.tool.ToolContext;
import com.szh.tool.ToolDefinition;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 全量写文件工具：创建新文件或整体覆盖已有文件，自动补齐缺失的父目录。
 * <p>
 * 定位与 edit_file 的分工（对齐主流 harness 的 Write/Edit 双工具范式）：
 * 新建文件或需要大规模重写时用 write_file；对已有文件做局部修改用 edit_file，
 * 避免模型为了改一行而重新生成整个文件（费 token 且容易引入无关 diff）。
 * 覆盖已有文件时结果里会回显旧文件行数，提醒模型这是破坏性操作。
 *
 * @author demussong
 * @date 2026/9/21
 */
@Slf4j
public class WriteFileTool extends FileToolSupport {

    public static final String CODE = "write_file";

    private static final ToolDefinition TOOL_DEFINITION = ToolDefinition.builder()
            .name(CODE)
            .code(CODE)
            .type("file")
            .description("写入文本文件（全量创建或覆盖）。适合新建文件；对已有文件做局部修改请优先用 edit_file。"
                    + "会自动创建缺失的父目录。path 支持工作区相对路径或绝对路径（写入默认仅限工作区内）")
            .parameters("{\"type\":\"object\",\"properties\":{"
                    + "\"path\":{\"type\":\"string\",\"description\":\"文件路径，相对路径以工作区为基准\"},"
                    + "\"content\":{\"type\":\"string\",\"description\":\"要写入的完整文件内容\"}},"
                    + "\"required\":[\"path\",\"content\"]}")
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
            checkWritable(file, toolContext);

            String content = rawText(args, "content");
            if (content == null) {
                throw new IllegalArgumentException("缺少必填参数 content（新建空文件请传空字符串）");
            }

            boolean existed = Files.exists(file);
            long oldLines = existed ? readLines(file).size() : 0;

            writeFile(file, content);

            int newLines = content.split("\n", -1).length;
            log.info("write_file: {}, existed={}, bytes={}, lines={}", file, existed, content.length(), newLines);
            return existed
                    ? "已覆盖写入 " + file + "（原 " + oldLines + " 行 -> 新 " + newLines + " 行，"
                        + content.length() + " 字符）"
                    : "已创建 " + file + "（" + newLines + " 行，" + content.length() + " 字符）";
        } catch (IllegalArgumentException e) {
            log.warn("write_file 参数错误, args={}, reason={}", toolContext.getArgs(), e.getMessage());
            return CODE + " 参数错误：" + e.getMessage();
        } catch (Exception e) {
            log.error("write_file 执行失败, args={}", toolContext.getArgs(), e);
            return CODE + " 执行失败：" + e.getMessage();
        }
    }
}
