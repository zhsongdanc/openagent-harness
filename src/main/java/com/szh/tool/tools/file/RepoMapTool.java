package com.szh.tool.tools.file;

import com.fasterxml.jackson.databind.JsonNode;
import com.szh.tool.ToolContext;
import com.szh.tool.ToolDefinition;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Repo Map 工具：生成项目结构树索引，让模型在大仓库里快速建立「地图」，
 * 不必靠一轮轮 ls/find 摸索目录（对标 Aider repo map / Cursor 项目索引的轻量版）。
 * <p>
 * 两级信息密度：
 * <ol>
 *   <li>tree 模式（默认）：目录树 + 文件清单，自动剪掉 .git/target/node_modules 等噪音目录；</li>
 *   <li>symbols 模式：对 Java 源文件额外抽取类型声明与方法/构造器签名（正则轻量抽取，
 *       不做完整语法解析），模型可据此直接定位「哪个类有哪个方法」再精确 read_file。</li>
 * </ol>
 * 输出条目受 maxEntries 限制，超限截断并提示缩小范围；超大输出仍会走落盘存根机制。
 *
 * @author demussong
 * @date 2026/9/21
 */
@Slf4j
public class RepoMapTool extends FileToolSupport {

    public static final String CODE = "repo_map";

    /**
     * 默认跳过的噪音目录：版本控制/构建产物/IDE 元数据/依赖缓存，对理解项目结构无价值
     */
    private static final Set<String> PRUNE_DIRS = Set.of(
            ".git", ".idea", ".vscode", ".agent-data", ".workbuddy",
            "target", "build", "out", "node_modules", "dist", "__pycache__", ".gradle");

    /**
     * Java 顶层类型声明：class/interface/enum/record/@interface
     */
    private static final Pattern JAVA_TYPE = Pattern.compile(
            "^\\s*(?:public\\s+|protected\\s+|private\\s+)?(?:static\\s+|final\\s+|abstract\\s+|sealed\\s+)*"
                    + "(class|interface|enum|record|@interface)\\s+(\\w+)");

    /**
     * Java 方法/构造器签名（启发式）：修饰符 + 返回类型 + 名字 + 参数表。
     * 返回类型限定为单 token（不含空白），避免字符类里带 \s 时贪婪后推把方法名吃进返回类型
     */
    private static final Pattern JAVA_METHOD = Pattern.compile(
            "^\\s*(?:public|protected|private)\\s+(?:static\\s+|final\\s+|abstract\\s+|synchronized\\s+|native\\s+)*"
                    + "(?:<[^>]+>\\s+)?([\\w$.]+(?:<[^;=]*?>)?(?:\\[\\])*)\\s+(\\w+)\\s*\\(");

    private static final ToolDefinition TOOL_DEFINITION = ToolDefinition.builder()
            .name(CODE)
            .code(CODE)
            .type("file")
            .description("生成项目结构地图（Repo Map）：输出目录树与文件清单，自动跳过 .git/target/node_modules 等噪音目录。"
                    + "mode=symbols 时对 Java 文件额外列出类与方法签名，适合在大项目中快速定位代码。"
                    + "path 缺省为工作区根目录")
            .parameters("{\"type\":\"object\",\"properties\":{"
                    + "\"path\":{\"type\":\"string\",\"description\":\"起始目录，默认工作区根\"},"
                    + "\"mode\":{\"type\":\"string\",\"enum\":[\"tree\",\"symbols\"],\"description\":\"tree=仅目录树(默认)；symbols=附带 Java 类/方法签名\"},"
                    + "\"maxDepth\":{\"type\":\"integer\",\"description\":\"最大目录深度，默认 6\"},"
                    + "\"maxEntries\":{\"type\":\"integer\",\"description\":\"最多输出条目数，默认 500\"}},"
                    + "\"required\":[]}")
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
            String rawPath = text(args, "path");
            Path root = rawPath == null
                    ? Path.of(toolContext.getWorkspace()).toAbsolutePath().normalize()
                    : resolvePath(rawPath, toolContext);
            checkReadable(root, toolContext);
            if (!Files.isDirectory(root)) {
                throw new IllegalArgumentException("不是目录: " + root);
            }

            boolean symbols = "symbols".equalsIgnoreCase(text(args, "mode"));
            int maxDepth = Math.max(1, integer(args, "maxDepth", 6));
            int maxEntries = Math.max(10, integer(args, "maxEntries", 500));

            List<String> lines = new ArrayList<>();
            int[] counts = walk(root, root, 0, maxDepth, maxEntries, symbols, lines);
            boolean truncated = counts[0] > maxEntries;

            StringBuilder sb = new StringBuilder();
            sb.append("Repo Map: ").append(root).append("\n");
            sb.append("模式: ").append(symbols ? "symbols" : "tree")
                    .append("，目录 ").append(counts[1]).append(" 个，文件 ").append(counts[2]).append(" 个");
            if (truncated) {
                sb.append("（已达 maxEntries=").append(maxEntries).append(" 上限被截断，建议用 path 缩小范围）");
            }
            sb.append("\n");
            for (String line : lines) {
                sb.append(line).append("\n");
            }
            return sb.toString();
        } catch (IllegalArgumentException e) {
            log.warn("repo_map 参数错误, args={}, reason={}", toolContext.getArgs(), e.getMessage());
            return CODE + " 参数错误：" + e.getMessage();
        } catch (Exception e) {
            log.error("repo_map 执行失败, args={}", toolContext.getArgs(), e);
            return CODE + " 执行失败：" + e.getMessage();
        }
    }

    /**
     * 深度优先遍历目录树，按「目录在前、名字字典序」输出缩进树。
     * 用数组承载可变计数：[0]=已输出条目 [1]=目录数 [2]=文件数（Java 无出参，避免为此建 DTO）
     */
    private int[] walk(Path root, Path dir, int depth, int maxDepth, int maxEntries,
                       boolean symbols, List<String> lines) throws IOException {
        int[] counts = new int[3];
        List<Path> children = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.forEach(children::add);
        }
        children.sort(Comparator.comparing((Path p) -> !Files.isDirectory(p))
                .thenComparing(p -> p.getFileName().toString().toLowerCase()));

        for (Path child : children) {
            if (counts[0] >= maxEntries) {
                return counts;
            }
            String name = child.getFileName().toString();
            if (Files.isDirectory(child)) {
                if (PRUNE_DIRS.contains(name) || name.startsWith(".")) {
                    continue;
                }
                counts[1]++;
                counts[0]++;
                lines.add(indent(depth) + name + "/");
                if (depth + 1 < maxDepth) {
                    int[] sub = walk(root, child, depth + 1, maxDepth, maxEntries - counts[0], symbols, lines);
                    counts[0] += sub[0];
                    counts[1] += sub[1];
                    counts[2] += sub[2];
                }
            } else {
                counts[2]++;
                counts[0]++;
                lines.add(indent(depth) + name);
                if (symbols && name.endsWith(".java")) {
                    for (String symbol : extractJavaSymbols(child)) {
                        if (counts[0] >= maxEntries) {
                            return counts;
                        }
                        counts[0]++;
                        lines.add(indent(depth + 1) + symbol);
                    }
                }
            }
        }
        return counts;
    }

    private static String indent(int depth) {
        return "  ".repeat(depth);
    }

    /**
     * 正则轻量抽取 Java 文件的类型与方法签名（单行签名才可靠，跨行参数表跳过），
     * 输出形如 "class Foo" / "  + bar(String, int)"。抽取失败静默跳过，不影响目录树主体
     */
    private List<String> extractJavaSymbols(Path file) {
        List<String> symbols = new ArrayList<>();
        try {
            if (Files.size(file) > 512 * 1024) {
                return symbols;
            }
            String currentType = null;
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String stripped = line.strip();
                // 跳过注释与注解行，减少误报
                if (stripped.startsWith("//") || stripped.startsWith("*") || stripped.startsWith("@")) {
                    continue;
                }
                Matcher typeMatcher = JAVA_TYPE.matcher(line);
                if (typeMatcher.find()) {
                    currentType = typeMatcher.group(2);
                    symbols.add(typeMatcher.group(1) + " " + currentType);
                    continue;
                }
                Matcher methodMatcher = JAVA_METHOD.matcher(line);
                if (methodMatcher.find()) {
                    String returnType = methodMatcher.group(1).strip();
                    String name = methodMatcher.group(2);
                    // 控制流关键字与构造器误匹配兜底（构造器无返回类型，签名价值低）
                    if (Set.of("if", "for", "while", "switch", "catch", "return", "new").contains(name)
                            || name.equals(currentType)) {
                        continue;
                    }
                    String params = line.substring(line.indexOf('(') + 1).replaceAll("\\).*$", "").strip();
                    symbols.add("+ " + name + "(" + simplifyParams(params) + ") : " + returnType);
                }
            }
        } catch (Exception e) {
            log.debug("extract java symbols failed: {}", file, e);
        }
        return symbols;
    }

    /**
     * 参数表只保留类型（去参数名），压缩签名长度：String name, int age -> String, int
     */
    private static String simplifyParams(String params) {
        if (params.isBlank()) {
            return "";
        }
        List<String> types = new ArrayList<>();
        int depth = 0;
        StringBuilder current = new StringBuilder();
        for (char c : params.toCharArray()) {
            if (c == '<') {
                depth++;
            } else if (c == '>') {
                depth--;
            }
            if (c == ',' && depth == 0) {
                types.add(lastTypeToken(current.toString()));
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        types.add(lastTypeToken(current.toString()));
        return String.join(", ", types);
    }

    private static String lastTypeToken(String param) {
        String[] tokens = param.strip().split("\\s+");
        // 形如 "final String name" -> "String"；"String... args" -> "String..."
        return tokens.length >= 2 ? tokens[tokens.length - 2] + (tokens[tokens.length - 1].contains("...") ? "..." : "")
                : param.strip();
    }
}
