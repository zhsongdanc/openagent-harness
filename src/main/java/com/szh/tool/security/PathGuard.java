package com.szh.tool.security;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * @author demussong
 * @describe 路径穿越防护：扫描命令参数中疑似路径的片段，归一化后校验是否落在允许范围内。
 * <p>
 * 防止模型通过 {@code cat ../../etc/passwd}、{@code rm /absolute/sensitive} 等方式越权访问工作区外的文件。
 * 判定逻辑：
 * <ol>
 *   <li>只有「看起来像路径」的参数才校验（含 '/'、或以 ./ ../ 开头），选项如 -la、--no-pager、HEAD~1 不会被误伤；</li>
 *   <li>相对路径以工作区根为基准解析，绝对路径原样解析，统一 normalize 消除 {@code ..} 段；</li>
 *   <li>归一化后若不在任一允许根（工作区 + 缓存 + 临时目录）之下，则记为越界。</li>
 * </ol>
 * 这是纵深防御的一层：即便 OS 沙箱被绕过或未启用，也能在参数层拦下越界访问。
 * @date 2026/9/21
 */
public class PathGuard {

    private PathGuard() {
    }

    /**
     * 路径校验结果
     */
    public static class PathCheck {
        private final boolean traversal;
        private final List<String> outsidePaths;

        PathCheck(boolean traversal, List<String> outsidePaths) {
            this.traversal = traversal;
            this.outsidePaths = outsidePaths;
        }

        /**
         * 是否出现 .. 穿越片段
         */
        public boolean hasTraversal() {
            return traversal;
        }

        /**
         * 落在允许范围之外的路径列表
         */
        public List<String> getOutsidePaths() {
            return outsidePaths;
        }

        /**
         * 是否存在任何越界访问
         */
        public boolean isOutside() {
            return !outsidePaths.isEmpty();
        }

        public boolean isClean() {
            return !traversal && outsidePaths.isEmpty();
        }
    }

    /**
     * 校验命令参数中的路径是否越界
     */
    public static PathCheck check(List<String> command, SandboxPolicy policy) {
        List<String> outside = new ArrayList<>();
        boolean traversal = false;
        if (command == null || command.size() <= 1) {
            return new PathCheck(false, outside);
        }
        Path base = policy.getWorkspaceRoot();
        // 从第二个元素开始（跳过可执行文件名）
        for (int i = 1; i < command.size(); i++) {
            String arg = command.get(i);
            if (!isPathLike(arg)) {
                continue;
            }
            if (arg.contains("..")) {
                traversal = true;
            }
            Path resolved = resolve(arg, base);
            if (!isWithinAllowed(resolved, policy)) {
                outside.add(arg);
            }
        }
        return new PathCheck(traversal, outside);
    }

    /**
     * 判断参数是否像路径：含分隔符，或以 ./ ../ 开头。纯选项与 glob 模式不算
     */
    static boolean isPathLike(String arg) {
        if (arg == null || arg.isEmpty()) {
            return false;
        }
        if (arg.startsWith("-")) {
            return false;
        }
        return arg.contains("/") || arg.startsWith("./") || arg.startsWith("../") || arg.equals("..");
    }

    /**
     * 相对路径以工作区为基准解析，绝对路径原样解析，再规范化并解析符号链接。
     * public 供文件工具（com.szh.tool.tools.file）复用，与 shell 层保持同一路径安全口径
     */
    public static Path resolve(String arg, Path base) {
        Path p = Paths.get(SandboxPolicy.expand(arg));
        Path absolute = (p.isAbsolute() ? p : base.resolve(p)).toAbsolutePath().normalize();
        return canonical(absolute);
    }

    /**
     * 解析符号链接为真实路径；路径不存在时，解析其最近的已存在祖先后再拼回剩余部分，
     * 与 {@link SandboxPolicy#real} 的可写根保持同一规范化口径（避免 /tmp vs /private/tmp 误判越界）。
     */
    static Path canonical(Path abs) {
        if (Files.exists(abs)) {
            return SandboxPolicy.real(abs);
        }
        Path existing = abs;
        Path remainder = Paths.get("");
        while (existing != null && !Files.exists(existing)) {
            Path name = existing.getFileName();
            remainder = name == null ? remainder : name.resolve(remainder);
            existing = existing.getParent();
        }
        if (existing == null) {
            return abs;
        }
        return SandboxPolicy.real(existing).resolve(remainder).normalize();
    }

    /**
     * 归一化路径是否落在任一允许根之下（含根本身）。public 供文件工具复用
     */
    public static boolean isWithinAllowed(Path resolved, SandboxPolicy policy) {
        for (Path root : policy.getWritableRoots()) {
            if (resolved.startsWith(root)) {
                return true;
            }
        }
        return false;
    }
}
