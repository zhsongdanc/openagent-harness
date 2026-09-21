package com.szh.tool.tools.file;

import com.fasterxml.jackson.databind.JsonNode;
import com.szh.tool.Tool;
import com.szh.tool.ToolContext;
import com.szh.tool.security.PathGuard;
import com.szh.tool.security.SandboxPolicy;
import com.szh.utils.ConfigUtil;
import com.szh.utils.JsonUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 文件类工具的公共基类：统一 JSON 入参解析与路径安全口径。
 * <p>
 * 路径安全（与 shell 安全层同一套规范，纵深防御）：
 * <ol>
 *   <li>相对路径以工作区根为基准解析，统一 normalize + toRealPath 消除 {@code ..} 与符号链接
 *       （复用 {@link PathGuard#resolve}，避免 /tmp vs /private/tmp 这类误判）；</li>
 *   <li>读取要求路径落在沙箱策略的允许根（工作区 + 缓存目录）内；</li>
 *   <li>写入默认只允许工作区内，工作区外需显式配置 file.tools.allowOutside=true，
 *       防止模型把宿主机任意文件当草稿纸。</li>
 * </ol>
 *
 * @author demussong
 * @date 2026/9/21
 */
@Slf4j
public abstract class FileToolSupport implements Tool {

    /**
     * 解析工具入参为 JsonNode，非法/空时返回空对象节点，避免 NPE
     */
    protected JsonNode parseArgs(String args) {
        String trimmed = args == null ? "" : args.trim();
        JsonNode node = trimmed.startsWith("{") ? JsonUtil.readTree(trimmed) : null;
        return node != null && node.isObject() ? node : JsonUtil.getMapper().createObjectNode();
    }

    protected String text(JsonNode args, String field) {
        JsonNode value = args.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text.isBlank() ? null : text.trim();
    }

    /**
     * 取原始字符串参数（不 trim）：content/old_text/new_text 的空白本身有语义，
     * 只有「完全缺失/null」才返回 null
     */
    protected String rawText(JsonNode args, String field) {
        JsonNode value = args.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.asText();
    }

    protected boolean booleanArg(JsonNode args, String field, boolean defaultValue) {
        JsonNode value = args.get(field);
        if (value == null || value.isNull()) {
            return defaultValue;
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        return Boolean.parseBoolean(value.asText().trim());
    }

    protected int integer(JsonNode args, String field, int defaultValue) {
        JsonNode value = args.get(field);
        if (value == null || value.isNull()) {
            return defaultValue;
        }
        if (value.isNumber()) {
            return value.asInt(defaultValue);
        }
        try {
            return Integer.parseInt(value.asText().trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * 把模型传入的 path 解析为规范化的绝对路径。
     * 解析失败（含 '/' 的非法字符等）抛 IllegalArgumentException，由子类转成工具错误结果。
     */
    protected Path resolvePath(String rawPath, ToolContext toolContext) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new IllegalArgumentException("缺少必填参数 path");
        }
        Path workspace = Paths.get(toolContext.getWorkspace()).toAbsolutePath().normalize();
        return PathGuard.resolve(rawPath.trim(), workspace);
    }

    /**
     * 读取权限校验：默认仅限工作区内。
     * 注意不能直接复用沙箱策略的允许根（writableRoots 含 /tmp、~/.m2 等构建缓存），
     * 否则工作区建在 /var/folders 临时目录下时，../ 穿越读 sibling 文件会被误判合法
     */
    protected void checkReadable(Path resolved, ToolContext toolContext) {
        SandboxPolicy policy = SandboxPolicy.fromConfig(toolContext.getWorkspace());
        if (resolved.startsWith(policy.getWorkspaceRoot())) {
            return;
        }
        if (ConfigUtil.getBoolean("file.tools.allowOutside", false)
                && PathGuard.isWithinAllowed(resolved, policy)) {
            return;
        }
        throw new IllegalArgumentException("路径 " + resolved + " 在工作区外，禁止读取"
                + "（如确需放开请配置 file.tools.allowOutside=true）");
    }

    /**
     * 写入权限校验：默认仅限工作区内；file.tools.allowOutside=true 时放开到策略允许根
     */
    protected void checkWritable(Path resolved, ToolContext toolContext) {
        SandboxPolicy policy = SandboxPolicy.fromConfig(toolContext.getWorkspace());
        Path workspaceRoot = policy.getWorkspaceRoot();
        if (resolved.startsWith(workspaceRoot)) {
            return;
        }
        if (ConfigUtil.getBoolean("file.tools.allowOutside", false)
                && PathGuard.isWithinAllowed(resolved, policy)) {
            return;
        }
        throw new IllegalArgumentException("路径 " + resolved + " 在工作区外，禁止写入"
                + "（如确需放开请配置 file.tools.allowOutside=true）");
    }

    /**
     * 读文件全部行（UTF-8）；文件不存在抛 IllegalArgumentException 提示模型改用 write_file
     */
    protected java.util.List<String> readLines(Path file) throws IOException {
        if (!Files.exists(file)) {
            throw new IllegalArgumentException("文件不存在: " + file);
        }
        if (Files.isDirectory(file)) {
            throw new IllegalArgumentException("目标是目录不是文件: " + file);
        }
        return Files.readAllLines(file, StandardCharsets.UTF_8);
    }

    /**
     * 写文件（UTF-8），自动创建缺失的父目录
     */
    protected void writeFile(Path file, String content) throws IOException {
        Path parent = file.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
