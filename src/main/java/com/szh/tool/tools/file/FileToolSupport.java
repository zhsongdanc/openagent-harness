package com.szh.tool.tools.file;

import com.fasterxml.jackson.databind.JsonNode;
import com.szh.tool.Tool;
import com.szh.tool.ToolContext;
import com.szh.tool.security.PathGuard;
import com.szh.tool.security.SandboxPolicy;
import com.szh.tool.store.FileSnapshotStore;
import com.szh.utils.ConfigUtil;
import com.szh.utils.JsonUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

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
     * 路径级写锁表：同一真实路径的写类操作（edit_file / write_file / delete_file）在进程内串行，
     * 不同路径保持并行。
     * <p>
     * 为什么必须有：ParallelToolExecutor 会把一轮内多个工具调用丢进线程池并发执行，而
     * edit_file 是「读全量 -> 字符串替换 -> 全量写回」的读-改-写，同文件两个并发编辑会各自
     * 读到同一份旧内容，后写者基于旧快照整体覆盖先写者——两边都回执"成功"，但先写的改动被
     * 静默丢失（实测：同轮两处编辑丢失 import）。把读-改-写整段收进同一路径锁内，
     * 后一个编辑必然在前一个落盘后才读文件，两处改动叠加保留。
     * <p>
     * key 用 resolvePath 产出的规范化真实路径（PathGuard 已解析符号链接），同一文件经不同
     * 别名（相对/绝对/tmp 软链）命中同一把锁；单进程 harness 里锁数量 = 被写过的文件数，
     * 每把锁几十字节，无需回收。
     */
    private static final ConcurrentHashMap<String, ReentrantLock> PATH_LOCKS = new ConcurrentHashMap<>();

    /**
     * 对同一文件的写操作加锁执行（可重入）。调用方把「读文件→校验→写文件」整段放进 action，
     * 锁的粒度即一次完整的读-改-写；纯计算/diff 生成也放进来无妨（不碰磁盘，不增加竞争窗口）。
     */
    protected <T> T withPathLock(Path file, Callable<T> action) throws Exception {
        ReentrantLock lock = PATH_LOCKS.computeIfAbsent(file.toString(), k -> new ReentrantLock());
        lock.lock();
        try {
            return action.call();
        } finally {
            lock.unlock();
        }
    }

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
        // 拒绝回执必须给出模型当下就能执行的出路（Plan B 范式）：只读命令在权限闸门下
        // 本来就放行，若不提示改用 shell，模型只会原样重试，攒满 LoopGuard 连续故障阈值。
        // 写类拒绝不加此提示：安全环境下 shell 写同样会被拦，提示反而是误导。
        throw new IllegalArgumentException("路径 " + resolved + " 在工作区外，禁止读取"
                + "（可配置 file.tools.allowOutside=true 放开到策略允许根；"
                + "如只是想查看该文件，可改用 shell 工具执行 cat/grep 配绝对路径读取）");
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
     * 写盘前捕获文件的 before-image，供 {@code /rewind} 回溯到历史时刻时还原（对标 Claude Code checkpoint）。
     * 每轮每文件幂等（只记首次改动前的镜像）；无 session/turnId 或回溯关闭时静默跳过，绝不拖垮写操作。
     * 由 {@code WriteFileTool} / {@code EditFileTool} 在真正落盘前调用。
     */
    protected void captureSnapshot(Path file, ToolContext toolContext) {
        FileSnapshotStore.capture(toolContext, file);
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
