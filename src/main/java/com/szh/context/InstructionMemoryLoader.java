package com.szh.context;

import com.szh.utils.ConfigUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 分层指令记忆加载器（借鉴 Codex 的 AGENTS.md 与 Claude Code 的 CLAUDE.md/@import 设计）。
 * <p>
 * 装配顺序（后者更具体、优先级更高，追加在末尾以便覆盖/细化前者）：
 * <ol>
 *   <li>内置基础 prompt（{@link #BASE_SYSTEM_PROMPT}）；</li>
 *   <li>全局用户级记忆：{@code ~/.openagent/AGENTS.md}；</li>
 *   <li>目录链记忆：从文件系统根向下到 workspace，逐级收集 {@code AGENTS.md}
 *       （即从 workspace 向上遍历到根，再反转为“根在前、workspace 在后”）。</li>
 * </ol>
 * 每个记忆文件支持 {@code @path} 语法递归引入其它文件（相对当前文件目录解析，
 * 支持 {@code ~/} 与绝对路径），带循环引用保护与最大深度限制。
 * <p>
 * 所有磁盘/配置异常均被内部消化，任何失败都退化为仅返回基础 prompt，绝不阻断 AgentState 构造。
 *
 * @author demussong
 * @date 2026/9/21
 */
@Slf4j
public class InstructionMemoryLoader {

    /**
     * 无任何记忆文件时的兜底 system prompt
     */
    public static final String BASE_SYSTEM_PROMPT = "你是一个人工智能助手，请回答用户问题。";

    private static final String ENABLED_KEY = "memory.instruction.enabled";
    private static final String FILENAME_KEY = "memory.instruction.filename";
    private static final String GLOBAL_DIR_KEY = "memory.instruction.globalDir";
    private static final String MAX_DEPTH_KEY = "memory.instruction.maxImportDepth";

    private static final String DEFAULT_FILENAME = "AGENTS.md";
    private static final String DEFAULT_GLOBAL_DIR = "~/.openagent";
    private static final int DEFAULT_MAX_DEPTH = 5;

    /**
     * {@code @path} 引入候选：仅作为候选，最终是否为引入由“文件存在 + 扩展名合法”双重判定，
     * 避免误伤 email 等含 @ 的普通文本。
     */
    private static final Pattern IMPORT_PATTERN = Pattern.compile("@([^\\s@]+)");

    /**
     * 围栏代码块起止（``` 或 ~~~），代码块内的 @ 不做引入解析
     */
    private static final Pattern FENCE_PATTERN = Pattern.compile("^\\s*(```|~~~)");

    private InstructionMemoryLoader() {
    }

    /**
     * 使用配置中的 project.workspace 作为目录链遍历起点装配 system prompt
     */
    public static String loadSystemPrompt() {
        return loadSystemPrompt(resolveWorkspace());
    }

    /**
     * 装配分层 system prompt；任何异常都退化为 {@link #BASE_SYSTEM_PROMPT}
     */
    public static String loadSystemPrompt(Path workspace) {
        try {
            if (!ConfigUtil.getBoolean(ENABLED_KEY, true)) {
                return BASE_SYSTEM_PROMPT;
            }
            String filename = ConfigUtil.get(FILENAME_KEY, DEFAULT_FILENAME);
            int maxDepth = ConfigUtil.getInt(MAX_DEPTH_KEY, DEFAULT_MAX_DEPTH);

            List<Source> sources = new ArrayList<>();
            // 全局用户级记忆
            Path globalFile = expandHome(ConfigUtil.get(GLOBAL_DIR_KEY, DEFAULT_GLOBAL_DIR)).resolve(filename);
            addSource(sources, globalFile, "global");
            // 目录链记忆（根在前、workspace 在后，越靠近 workspace 越后应用、优先级越高）
            for (Path p : collectUpward(workspace, filename)) {
                addSource(sources, p, "project");
            }

            if (sources.isEmpty()) {
                return BASE_SYSTEM_PROMPT;
            }

            StringBuilder sb = new StringBuilder(BASE_SYSTEM_PROMPT);
            sb.append("\n\n===== Instruction Memory =====\n");
            boolean any = false;
            for (Source s : sources) {
                String content = readAndExpand(s.path, maxDepth);
                if (content == null || content.isBlank()) {
                    continue;
                }
                sb.append("\n[scope=").append(s.scope).append(" | source=").append(s.path).append("]\n")
                        .append(content.strip()).append("\n");
                any = true;
            }
            if (!any) {
                return BASE_SYSTEM_PROMPT;
            }
            sb.append("==============================");
            String prompt = sb.toString();
            log.info("InstructionMemoryLoader: assembled system prompt from {} source(s), length={}",
                    sources.size(), prompt.length());
            return prompt;
        } catch (RuntimeException e) {
            log.error("InstructionMemoryLoader: load failed, fallback to base prompt", e);
            return BASE_SYSTEM_PROMPT;
        }
    }

    /**
     * 从 workspace 目录向上遍历到文件系统根，收集存在的记忆文件；
     * 返回结果反转为“根在前、workspace 在后”，使更具体的目录指令后应用、优先级更高。
     */
    private static List<Path> collectUpward(Path workspace, String filename) {
        List<Path> found = new ArrayList<>();
        if (workspace == null) {
            return found;
        }
        Path dir = workspace.toAbsolutePath().normalize();
        while (dir != null) {
            Path candidate = dir.resolve(filename);
            if (Files.isRegularFile(candidate)) {
                found.add(candidate);
            }
            dir = dir.getParent();
        }
        Collections.reverse(found);
        return found;
    }

    private static void addSource(List<Source> sources, Path file, String scope) {
        if (file != null && Files.isRegularFile(file)) {
            sources.add(new Source(file, scope));
        }
    }

    /**
     * 读取文件并递归展开其中的 {@code @path} 引入
     */
    private static String readAndExpand(Path file, int maxDepth) {
        Set<String> visited = new LinkedHashSet<>();
        String key = canonical(file);
        if (key != null) {
            visited.add(key);
        }
        return expand(readFile(file), file.toAbsolutePath().getParent(), visited, maxDepth);
    }

    /**
     * 逐行展开引入：跳过围栏代码块；对每个可解析为合法存在文件的 {@code @path}，
     * 用其（递归展开后的）正文就地替换该 token。
     */
    private static String expand(String content, Path baseDir, Set<String> visited, int depth) {
        if (content == null) {
            return "";
        }
        if (depth <= 0) {
            return content;
        }
        List<String> out = new ArrayList<>();
        boolean inFence = false;
        for (String line : Arrays.asList(content.split("\n", -1))) {
            if (FENCE_PATTERN.matcher(line).find()) {
                inFence = !inFence;
                out.add(line);
                continue;
            }
            if (inFence) {
                out.add(line);
                continue;
            }
            out.add(expandLine(line, baseDir, visited, depth));
        }
        return String.join("\n", out);
    }

    private static String expandLine(String line, Path baseDir, Set<String> visited, int depth) {
        Matcher m = IMPORT_PATTERN.matcher(line);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (m.find()) {
            Path imported = resolveImport(m.group(1), baseDir);
            if (imported == null) {
                continue;
            }
            String key = canonical(imported);
            if (key == null || !visited.add(key)) {
                // 已引入过（或无法定位），跳过以避免循环/重复
                continue;
            }
            String child = expand(readFile(imported), imported.toAbsolutePath().getParent(), visited, depth - 1);
            sb.append(line, last, m.start());
            sb.append("\n").append(child.strip()).append("\n");
            last = m.end();
        }
        sb.append(line.substring(last));
        return sb.toString();
    }

    /**
     * 解析引入 token 为真实存在的文件路径；非法/不存在返回 null（token 原样保留）。
     * 仅接受 .md/.markdown/.txt，避免引入任意文件。
     */
    private static Path resolveImport(String token, Path baseDir) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String t = token.replaceAll("[),;:'\"]+$", "");
        String lower = t.toLowerCase();
        if (!(lower.endsWith(".md") || lower.endsWith(".markdown") || lower.endsWith(".txt"))) {
            return null;
        }
        Path p;
        if (t.startsWith("~")) {
            p = expandHome(t);
        } else if (t.startsWith("/")) {
            p = Path.of(t);
        } else {
            p = (baseDir == null ? Path.of("").toAbsolutePath() : baseDir).resolve(t);
        }
        p = p.normalize();
        return Files.isRegularFile(p) ? p : null;
    }

    private static String readFile(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            log.warn("InstructionMemoryLoader: read file failed: {}", file, e);
            return "";
        }
    }

    private static Path resolveWorkspace() {
        return Path.of(ConfigUtil.get("project.workspace", System.getProperty("user.dir")));
    }

    private static Path expandHome(String path) {
        if (path != null && path.startsWith("~")) {
            return Path.of(System.getProperty("user.home"), path.substring(1).replaceFirst("^/", ""));
        }
        return Path.of(path);
    }

    private static String canonical(Path p) {
        try {
            return p.toRealPath().toString();
        } catch (IOException | RuntimeException e) {
            return p.toAbsolutePath().normalize().toString();
        }
    }

    private record Source(Path path, String scope) {
    }
}
