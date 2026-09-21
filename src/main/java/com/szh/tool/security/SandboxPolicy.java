package com.szh.tool.security;

import com.szh.utils.ConfigUtil;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @author demussong
 * @describe 沙箱与权限的静态策略配置：一次装配、整个 run 复用。
 * <p>
 * 由 {@link #fromConfig(String)} 从 application.properties 读取，承载四类信息：
 * <ul>
 *   <li>{@link #mode}：授权档位（read-only / workspace-write / full-access）；</li>
 *   <li>{@link #workspaceRoot}：工作区根，路径穿越防护的基准；</li>
 *   <li>{@link #writableRoots}：允许写入的目录集合（工作区 + 构建缓存 + 临时目录），
 *       workspace-write 档位下沙箱只放开这些目录的写权限；</li>
 *   <li>{@link #networkTools}：联网豁免名单，workspace-write 默认禁网，只有名单内的可执行文件
 *       （如 git、mvn）才被放开网络，因为它们常需拉取远端依赖。</li>
 * </ul>
 * @date 2026/9/21
 */
public class SandboxPolicy {

    private final SandboxMode mode;
    private final Path workspaceRoot;
    private final Set<Path> writableRoots;
    private final Set<String> networkTools;
    private final boolean sandboxEnabled;

    public SandboxPolicy(SandboxMode mode, Path workspaceRoot, Set<Path> writableRoots,
                         Set<String> networkTools, boolean sandboxEnabled) {
        this.mode = mode;
        this.workspaceRoot = workspaceRoot;
        this.writableRoots = writableRoots;
        this.networkTools = networkTools;
        this.sandboxEnabled = sandboxEnabled;
    }

    /**
     * 从配置装配策略。工作区取参数（运行时已知），其余走 ConfigUtil 统一读取。
     * 所有异常降级为 FULL_ACCESS + 沙箱关闭，保证策略层出问题时不阻断正常执行。
     */
    public static SandboxPolicy fromConfig(String workspace) {
        try {
            Path root = real(Paths.get(expand(workspace != null ? workspace : System.getProperty("user.dir"))));
            SandboxMode mode = SandboxMode.from(ConfigUtil.get("shell.sandbox.mode", "workspace-write"));
            boolean enabled = ConfigUtil.getBoolean("shell.sandbox.enabled", true);

            Set<Path> writable = new LinkedHashSet<>();
            writable.add(root);
            // 构建工具默认要写缓存目录，否则 mvn/gradle 在沙箱里必然失败
            for (String extra : splitCsv(ConfigUtil.get("shell.sandbox.writable.extra",
                    "~/.m2,~/.gradle,~/.openagent,/tmp,/private/tmp,/var/folders"))) {
                writable.add(real(Paths.get(expand(extra))));
            }

            Set<String> networkTools = splitCsv(ConfigUtil.get("shell.sandbox.network.tools", "git,mvn"))
                    .stream().map(SandboxPolicy::basename).collect(Collectors.toCollection(LinkedHashSet::new));

            return new SandboxPolicy(mode, root, writable, networkTools, enabled);
        } catch (Exception e) {
            // 策略装配失败时退化为不限制，避免误伤可用性
            return new SandboxPolicy(SandboxMode.FULL_ACCESS,
                    Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize(),
                    Set.of(), Set.of(), false);
        }
    }

    /**
     * 解析为真实路径（消除符号链接）。macOS 上 /tmp→/private/tmp、/var/folders→/private/var/folders，
     * 而 Seatbelt 的 subpath 与路径包含判断都按规范化真实路径匹配，不解析会导致这些目录被误判越界。
     * 路径尚不存在时退回绝对归一化路径（如首次运行时的 ~/.m2）。
     */
    static Path real(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException | SecurityException e) {
            return p.toAbsolutePath().normalize();
        }
    }

    public SandboxMode getMode() {
        return mode;
    }

    public Path getWorkspaceRoot() {
        return workspaceRoot;
    }

    public Set<Path> getWritableRoots() {
        return writableRoots;
    }

    public Set<String> getNetworkTools() {
        return networkTools;
    }

    public boolean isSandboxEnabled() {
        return sandboxEnabled;
    }

    /**
     * 该可执行文件在当前策略下是否被允许联网
     */
    public boolean isNetworkAllowed(String executable) {
        return mode == SandboxMode.FULL_ACCESS || networkTools.contains(basename(executable));
    }

    /**
     * 展开 ~ 为当前用户 home
     */
    static String expand(String path) {
        if (path == null) {
            return null;
        }
        String trimmed = path.trim();
        if (trimmed.equals("~")) {
            return System.getProperty("user.home");
        }
        if (trimmed.startsWith("~/")) {
            return System.getProperty("user.home") + trimmed.substring(1);
        }
        return trimmed;
    }

    static Set<String> splitCsv(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * 取路径末段作为可执行文件名（兼容传入绝对路径的 exe）
     */
    static String basename(String path) {
        if (path == null) {
            return "";
        }
        int idx = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return idx >= 0 ? path.substring(idx + 1) : path;
    }
}
