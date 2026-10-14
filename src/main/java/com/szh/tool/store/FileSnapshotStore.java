package com.szh.tool.store;

import com.szh.tool.ToolContext;
import com.szh.utils.CommonUtils;
import com.szh.utils.ConfigUtil;
import com.szh.utils.JsonUtil;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 文件快照存储：对标 Claude Code 的 {@code /rewind} before-image 机制，为「回溯到历史时刻」补上文件侧能力。
 * <p>
 * 与 {@link ToolResultStore} 同一套落盘范式，路径 {@code {workspace}/.agent-data/snapshots/{sessionId}/{turnId}/}，
 * 每轮一个目录，内含 {@code manifest.json}（本轮改动清单）+ 若干 {@code N.bak}（改动前的原始字节）。
 * <p>
 * 语义与边界（务必对齐 Claude Code，避免过度承诺）：
 * <ol>
 *   <li><b>只覆盖 write_file / edit_file 自己改动的文件</b>——在真正落盘前由 {@code FileToolSupport} 调
 *       {@link #capture} 拍下 before-image；shell/git/子 agent 的副作用不在追踪范围（连 Claude Code 也不管 Bash）。</li>
 *   <li><b>每轮每文件只拍一次</b>（{@link #capture} 幂等）：保存的是「本轮首次改动前」的镜像，
 *       即该轮开始时的文件状态，正好是回溯到「本轮之前」需要还原的目标。</li>
 *   <li><b>回溯到第 N 轮之前</b>（{@link #restoreToTurn}）：对每个文件取「turnSeq &gt;= N 中最早的那份快照」还原——
 *       该镜像即文件在第 N 轮之前的内容；若快照记录文件当时不存在（本轮之后才新建），则删除它。</li>
 *   <li>还原后清理 turnSeq &gt;= N 的快照目录（这些轮次已被撤销，其快照失效，也避免续跑时 turnId 复用撞旧目录）。</li>
 * </ol>
 * 总开关 {@code rewind.enabled}（默认 true）；关闭后 {@link #capture} 静默 no-op，不产生任何落盘。
 * 脱离运行时的单测直调（无 sessionId/turnId）同样静默跳过，保证文件工具可独立测试。
 *
 * @author demussong
 * @date 2026/10/12
 */
@Slf4j
public class FileSnapshotStore {

    /** 回溯能力总开关：关闭后不拍快照、/rewind 提示未启用 */
    public static final String ENABLED_KEY = "rewind.enabled";

    private static final String MANIFEST = "manifest.json";

    private FileSnapshotStore() {
    }

    /**
     * 某一轮的改动清单：turnId 归档、turnSeq 供排序比较、entries 记录本轮首次改动前的每个文件镜像。
     */
    @Data
    public static class Manifest {
        private String turnId;
        private int turnSeq;
        private long timestamp;
        private List<Entry> entries = new ArrayList<>();
    }

    /**
     * 单个文件的快照条目。
     */
    @Data
    public static class Entry {
        /** 文件的绝对真实路径（还原时按它定位） */
        private String path;
        /** 相对工作区路径，仅用于展示 */
        private String rel;
        /** 改动前文件是否已存在；false 表示该文件是本轮及之后新建的，还原时应删除 */
        private boolean existed;
        /** 备份文件名（存改动前字节）；existed=false 时为 null */
        private String backup;
    }

    /**
     * 一次还原的结果统计：还原（覆盖回旧内容）与删除（撤销新建）的文件数与路径清单。
     */
    @Data
    public static class RestoreResult {
        private int restored;
        private int deleted;
        private List<String> paths = new ArrayList<>();
    }

    public static boolean enabled() {
        return ConfigUtil.getBoolean(ENABLED_KEY, true);
    }

    /**
     * 写盘前捕获文件的 before-image（每轮每文件幂等）。
     * <p>
     * 无 sessionId/turnId（脱离运行时的单测）或回溯关闭时静默跳过；IO 异常只告警不抛出，
     * 绝不让「快照失败」拖垮正常的写文件操作。
     */
    public static void capture(ToolContext ctx, Path file) {
        if (!enabled() || ctx == null || file == null) {
            return;
        }
        String sessionId = ctx.getSessionId();
        String turnId = ctx.getTurnId();
        if (sessionId == null || sessionId.isBlank() || turnId == null || turnId.isBlank()) {
            return;
        }
        try {
            String real = realPath(file);
            Path dir = turnDir(ctx.getWorkspace(), sessionId, turnId);
            Manifest manifest = loadManifest(dir, turnId);
            // 幂等：本轮已快照过该文件则跳过，保留「首次改动前」的镜像
            for (Entry e : manifest.getEntries()) {
                if (real.equals(e.getPath())) {
                    return;
                }
            }
            Files.createDirectories(dir);
            Entry entry = new Entry();
            entry.setPath(real);
            entry.setRel(relativize(ctx.getWorkspace(), real));
            boolean existed = Files.exists(file);
            entry.setExisted(existed);
            if (existed) {
                String backupName = manifest.getEntries().size() + ".bak";
                Files.copy(file, dir.resolve(backupName), StandardCopyOption.REPLACE_EXISTING);
                entry.setBackup(backupName);
            }
            manifest.getEntries().add(entry);
            saveManifest(dir, manifest);
            log.info("FileSnapshotStore: captured {} turn={} existed={} rel={}",
                    real, turnId, existed, entry.getRel());
        } catch (IOException | RuntimeException e) {
            log.warn("FileSnapshotStore: capture failed for {}: {}", file, e.getMessage());
        }
    }

    /**
     * 列举某 session 的全部检查点（有文件改动的轮次），按 turnSeq 升序。
     */
    public static List<Manifest> listCheckpoints(String workspace, String sessionId) {
        List<Manifest> result = new ArrayList<>();
        Path sessionDir = sessionDir(workspace, sessionId);
        if (!Files.isDirectory(sessionDir)) {
            return result;
        }
        try (Stream<Path> dirs = Files.list(sessionDir)) {
            for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                Manifest m = loadManifest(dir, dir.getFileName().toString());
                if (m != null && !m.getEntries().isEmpty()) {
                    result.add(m);
                }
            }
        } catch (IOException | RuntimeException e) {
            log.warn("FileSnapshotStore: list checkpoints failed for session {}: {}", sessionId, e.getMessage());
        }
        result.sort(Comparator.comparingInt(Manifest::getTurnSeq));
        return result;
    }

    /**
     * 回溯到第 {@code targetSeq} 轮<b>之前</b>的文件状态：撤销 targetSeq 及其之后所有轮次对文件的改动。
     * <p>
     * 对每个受影响文件取「turnSeq &gt;= targetSeq 中最早的快照」还原（即该轮之前的镜像）；
     * 快照记录为「新建」的文件直接删除。还原成功后清理 turnSeq &gt;= targetSeq 的快照目录。
     */
    public static RestoreResult restoreToTurn(String workspace, String sessionId, int targetSeq) {
        RestoreResult result = new RestoreResult();
        List<Manifest> affected = listCheckpoints(workspace, sessionId).stream()
                .filter(m -> m.getTurnSeq() >= targetSeq)
                .sorted(Comparator.comparingInt(Manifest::getTurnSeq))
                .toList();
        if (affected.isEmpty()) {
            return result;
        }
        // 每个文件只认「最早的 >= targetSeq 快照」——那才是它在目标时刻之前的样子
        Map<String, Located> earliest = new LinkedHashMap<>();
        for (Manifest m : affected) {
            for (Entry e : m.getEntries()) {
                earliest.putIfAbsent(e.getPath(), new Located(m, e));
            }
        }
        for (Located loc : earliest.values()) {
            Entry e = loc.entry();
            Path dir = turnDir(workspace, sessionId, loc.manifest().getTurnId());
            Path target = Path.of(e.getPath());
            try {
                if (e.isExisted()) {
                    Path backup = dir.resolve(e.getBackup());
                    if (Files.exists(backup)) {
                        Path parent = target.getParent();
                        if (parent != null) {
                            Files.createDirectories(parent);
                        }
                        Files.copy(backup, target, StandardCopyOption.REPLACE_EXISTING);
                        result.setRestored(result.getRestored() + 1);
                        result.getPaths().add(e.getRel());
                    }
                } else if (Files.deleteIfExists(target)) {
                    // 目标轮次之后才新建的文件：回溯即删除
                    result.setDeleted(result.getDeleted() + 1);
                    result.getPaths().add(e.getRel());
                }
            } catch (IOException | RuntimeException ex) {
                log.warn("FileSnapshotStore: restore failed for {}: {}", e.getPath(), ex.getMessage());
            }
        }
        // 清理已撤销轮次的快照目录：避免失效快照残留，也避免续跑时 turnId 复用撞旧目录导致幂等误判
        for (Manifest m : affected) {
            deleteRecursively(turnDir(workspace, sessionId, m.getTurnId()));
        }
        log.info("FileSnapshotStore: restored to before turn {}, restored={}, deleted={}",
                targetSeq, result.getRestored(), result.getDeleted());
        return result;
    }

    /** 定位某 session 的快照根目录 */
    public static Path sessionDir(String workspace, String sessionId) {
        return Path.of(workspace, ".agent-data", "snapshots", sanitize(sessionId));
    }

    /** 定位某 session 某一轮的快照目录 */
    public static Path turnDir(String workspace, String sessionId, String turnId) {
        return sessionDir(workspace, sessionId).resolve(sanitize(turnId));
    }

    private record Located(Manifest manifest, Entry entry) {
    }

    private static Manifest loadManifest(Path dir, String turnId) {
        Path file = dir.resolve(MANIFEST);
        if (Files.exists(file)) {
            try {
                Manifest m = JsonUtil.parse(Files.readString(file), Manifest.class);
                if (m != null) {
                    if (m.getEntries() == null) {
                        m.setEntries(new ArrayList<>());
                    }
                    return m;
                }
            } catch (IOException | RuntimeException e) {
                log.warn("FileSnapshotStore: load manifest failed at {}: {}", file, e.getMessage());
            }
        }
        Manifest m = new Manifest();
        m.setTurnId(turnId);
        m.setTurnSeq(CommonUtils.parseTurnSeq(turnId));
        m.setTimestamp(System.currentTimeMillis());
        return m;
    }

    private static void saveManifest(Path dir, Manifest manifest) {
        Path file = dir.resolve(MANIFEST);
        try {
            Files.writeString(file, JsonUtil.toPrettyJson(manifest));
        } catch (IOException | RuntimeException e) {
            log.warn("FileSnapshotStore: save manifest failed at {}: {}", file, e.getMessage());
        }
    }

    private static void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("FileSnapshotStore: delete failed {}: {}", p, e.getMessage());
                }
            });
        } catch (IOException | RuntimeException e) {
            log.warn("FileSnapshotStore: cleanup failed at {}: {}", dir, e.getMessage());
        }
    }

    /**
     * 规范化为绝对真实路径（解析符号链接，与 PathGuard/SandboxPolicy 同一口径）；失败退化为绝对归一化路径。
     */
    private static String realPath(Path file) {
        try {
            return file.toRealPath().toString();
        } catch (IOException e) {
            return file.toAbsolutePath().normalize().toString();
        }
    }

    /**
     * 计算相对工作区的展示路径；不在工作区内或解析失败时回退为绝对路径。
     */
    private static String relativize(String workspace, String real) {
        try {
            Path root = Path.of(workspace).toAbsolutePath().normalize();
            Path target = Path.of(real);
            Path rel = root.relativize(target);
            String s = rel.toString();
            return s.startsWith("..") ? real : s;
        } catch (RuntimeException e) {
            return real;
        }
    }

    /**
     * 规范化目录名为文件系统安全字符串，避免路径穿越；空值兜底 default。
     */
    private static String sanitize(String name) {
        if (name == null || name.isBlank()) {
            return "default";
        }
        String cleaned = name.trim().replaceAll("[^a-zA-Z0-9_-]", "_");
        return cleaned.isEmpty() ? "default" : cleaned;
    }
}
