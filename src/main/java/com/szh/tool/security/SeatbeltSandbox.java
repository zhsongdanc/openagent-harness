package com.szh.tool.security;

import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author demussong
 * @describe macOS Seatbelt 沙箱：用系统自带的 {@code sandbox-exec} + SBPL 策略文件对命令做进程级隔离。
 * <p>
 * 这正是 Codex CLI 在 macOS 上的做法——不依赖 Docker，直接用内核沙箱原语，零启动开销。
 * 生成的策略遵循「默认全拒 + 显式放开」：
 * <ul>
 *   <li>读：放开（{@code allow file-read*}），保证工具能读 JDK、系统库、工作区；</li>
 *   <li>写：只放开 {@link SandboxPolicy#getWritableRoots()}（工作区 + 构建缓存 + 临时目录）；
 *       READ_ONLY 档位下几乎不放开写；</li>
 *   <li>网：默认禁网，仅当命令的可执行文件在联网豁免名单内才放开。</li>
 * </ul>
 * 策略文件按「档位 + 是否联网」缓存，避免每条命令都写临时文件。
 * @date 2026/9/21
 */
@Slf4j
public class SeatbeltSandbox implements SandboxExecutor {

    private static final String SANDBOX_EXEC = "/usr/bin/sandbox-exec";

    /**
     * profile 缓存：key = mode + ":" + networkAllowed
     */
    private final Map<String, Path> profileCache = new ConcurrentHashMap<>();

    @Override
    public List<String> wrap(List<String> command, SandboxPolicy policy) {
        // FULL_ACCESS 或沙箱不可用时不包装
        if (policy.getMode() == SandboxMode.FULL_ACCESS || !isAvailable()) {
            return command;
        }
        try {
            String exe = SandboxPolicy.basename(command.get(0));
            boolean network = policy.isNetworkAllowed(exe);
            Path profile = profileFor(policy, network);

            List<String> wrapped = new ArrayList<>();
            wrapped.add(SANDBOX_EXEC);
            wrapped.add("-f");
            wrapped.add(profile.toString());
            wrapped.addAll(command);
            return wrapped;
        } catch (Exception e) {
            // 生成 profile 失败时降级为直接执行，不阻断（权限层仍生效）
            log.error("build seatbelt profile failed, fallback to direct exec", e);
            return command;
        }
    }

    @Override
    public String name() {
        return "seatbelt";
    }

    @Override
    public boolean isAvailable() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        boolean mac = os.contains("mac") || os.contains("darwin");
        return mac && Files.isExecutable(Path.of(SANDBOX_EXEC));
    }

    /**
     * 取（或生成）对应档位与联网策略的 profile 文件
     */
    private Path profileFor(SandboxPolicy policy, boolean network) throws Exception {
        String key = policy.getMode() + ":" + network;
        Path cached = profileCache.get(key);
        if (cached != null && Files.exists(cached)) {
            return cached;
        }
        String sbpl = buildProfile(policy, network);
        Path file = Files.createTempFile("openagent-sandbox-", ".sb");
        Files.writeString(file, sbpl, StandardCharsets.UTF_8);
        file.toFile().deleteOnExit();
        profileCache.put(key, file);
        log.debug("generated seatbelt profile [{}]: {}", key, file);
        return file;
    }

    /**
     * 生成 SBPL 策略文本
     */
    private String buildProfile(SandboxPolicy policy, boolean network) {
        StringBuilder sb = new StringBuilder();
        sb.append("(version 1)\n");
        sb.append("(deny default)\n");
        // 进程与系统基础能力：fork/exec、读系统信息、mach 服务查找、信号
        sb.append("(allow process-fork)\n");
        sb.append("(allow process-exec)\n");
        sb.append("(allow process-exec-interpreter)\n");
        sb.append("(allow sysctl-read)\n");
        sb.append("(allow mach-lookup)\n");
        sb.append("(allow signal)\n");
        sb.append("(allow ipc-posix-shm)\n");
        // 读全放开：工具需读 JDK、系统库、工作区
        sb.append("(allow file-read*)\n");
        // 设备节点写入（/dev/null、/dev/stdout 等），很多命令依赖
        sb.append("(allow file-write-data\n");
        sb.append("  (literal \"/dev/null\")\n");
        sb.append("  (literal \"/dev/stdout\")\n");
        sb.append("  (literal \"/dev/stderr\")\n");
        sb.append("  (literal \"/dev/dtracehelper\"))\n");
        sb.append("(allow file-ioctl)\n");

        // 写权限：READ_ONLY 档位不放开任何目录写；其余放开可写根
        if (policy.getMode() != SandboxMode.READ_ONLY && !policy.getWritableRoots().isEmpty()) {
            sb.append("(allow file-write*\n");
            for (Path root : policy.getWritableRoots()) {
                sb.append("  (subpath \"").append(escape(root.toString())).append("\")\n");
            }
            sb.append(")\n");
            // 允许在可写根下创建/删除文件（file-write* 已含，但显式补充 unlink/link 更稳）
            sb.append("(allow file-write-create file-write-unlink file-write-mode file-write-owner\n");
            for (Path root : policy.getWritableRoots()) {
                sb.append("  (subpath \"").append(escape(root.toString())).append("\")\n");
            }
            sb.append(")\n");
        }

        // 网络：默认禁网，豁免工具放开
        if (network) {
            sb.append("(allow network*)\n");
        } else {
            sb.append("(deny network*)\n");
        }
        return sb.toString();
    }

    /**
     * 转义 SBPL 字符串里的反斜杠与双引号
     */
    private String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
