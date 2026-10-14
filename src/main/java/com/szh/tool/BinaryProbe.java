package com.szh.tool;

import com.szh.utils.ConfigUtil;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author demussong
 * @describe 外部可执行文件探测：按 PATH 判断某个命令在本机是否存在，供 {@link ToolRegistry} 按需注册工具时把关。
 * <p>
 * 设计要点：
 * 1. 纯文件系统 stat，不起子进程（不调 {@code which}/{@code command -v}），零副作用、跨平台；
 * 2. 结果进程内缓存，{@code rebuild} 反复调用也只扫一次 PATH；
 * 3. 保守降级——探测开关关闭或 PATH 取不到时一律返回「可用」，宁可不滤也不误滤，
 *    避免在探测不可靠的环境里把 git/mvn 等正常工具错杀。
 * @date 2026/10/14
 */
public final class BinaryProbe {

    /**
     * binary -> 是否存在于 PATH 的进程内缓存
     */
    private static final Map<String, Boolean> CACHE = new ConcurrentHashMap<>();

    private BinaryProbe() {
    }

    /**
     * 判断命令是否可在本机执行。
     *
     * @param binary 可执行文件名（如 {@code git}），非绝对路径
     * @return binary 为 null/空白、或探测被关闭、或 PATH 不可得时返回 true（视为可用不过滤）；
     * 否则按 PATH 目录实际扫描结果返回
     */
    public static boolean isAvailable(String binary) {
        if (binary == null || binary.isBlank()) {
            return true;
        }
        // 探测关闭：不做 PATH 扫描，一律视为可用（供单测 / PATH 不可靠环境降级）
        if (!ConfigUtil.getBoolean("tool.probe.binaries.enabled", true)) {
            return true;
        }
        return CACHE.computeIfAbsent(binary.trim(), BinaryProbe::scanPath);
    }

    /**
     * 清空缓存，供单测在切换配置 / 篡改 PATH 后重置判定结果。
     */
    public static void clearCache() {
        CACHE.clear();
    }

    private static boolean scanPath(String binary) {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            // 取不到 PATH，无从判定，按可用处理避免误滤
            return true;
        }
        boolean windows = isWindows();
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            File candidate = new File(dir, binary);
            if (existsExecutable(candidate)) {
                return true;
            }
            // Windows 下命令常带扩展名，PATH 里的名字不含后缀时需补试
            if (windows) {
                for (String ext : new String[]{".exe", ".cmd", ".bat"}) {
                    File withExt = new File(dir, binary + ext);
                    if (existsExecutable(withExt)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean existsExecutable(File file) {
        return file.isFile() && file.canExecute();
    }

    private static boolean isWindows() {
        String os = System.getProperty("os.name", "");
        return os.toLowerCase().contains("win");
    }
}
