package com.szh.memory;

import com.szh.utils.CommonUtils;
import com.szh.utils.ConfigUtil;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 基于 SQLite 的记忆候选层存储：与正式库同一个 {@code ~/.openagent/memory.db}，
 * 但建的是<b>两张普通表</b>（memory_candidates / memory_consolidation_log），不动 FTS5 虚拟表——
 * memory_fts 加列需整表重建迁移，而候选/日志按 id 与 fingerprint 点查、按时间清理，无需全文索引。
 * <p>构造即幂等建表；驱动缺失/建表失败抛异常，由 {@link LongTermMemory} 捕获后
 * 候选层整体退化（自动通道仍整理落正式库，只是无候选记录与日志），主链路不受影响。
 * 连接按操作开合（本地文件、开销小），与 {@link SqliteMemoryStore} 同一策略。
 *
 * @author demussong
 * @date 2026-10-16
 */
@Slf4j
public class SqliteCandidateStore implements CandidateStore {

    private static final String CANDIDATE_TABLE = "memory_candidates";
    private static final String LOG_TABLE = "memory_consolidation_log";

    private static final String CANDIDATE_COLS = "id, fingerprint, title, content, keywords, category, scope,"
            + " source_type, evidence, confidence, status, decision, result_memory_id, note,"
            + " project_key, source_session_id, created_at, processed_at";

    // OR IGNORE：命中 fingerprint UNIQUE 冲突时静默跳过（返回 0 行），由 insertIfAbsent 翻译为幂等语义
    private static final String INSERT_IGNORE_SQL = "INSERT OR IGNORE INTO " + CANDIDATE_TABLE + " (" + CANDIDATE_COLS
            + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

    /** upsert 用：先删同 id 行再普通插入，真冲突（异行同指纹）应报错暴露而非静默吞掉 */
    private static final String INSERT_SQL = "INSERT INTO " + CANDIDATE_TABLE + " (" + CANDIDATE_COLS
            + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

    private final String jdbcUrl;

    public SqliteCandidateStore() {
        // 与正式记忆库共用同一 db 文件（memory.sqlite.path），避免多一套路径配置
        String path = expandHome(ConfigUtil.get("memory.sqlite.path", "~/.openagent/memory.db"));
        ensureParentDir(path);
        this.jdbcUrl = "jdbc:sqlite:" + path;
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("sqlite-jdbc driver not found on classpath", e);
        }
        initSchema();
        log.info("SqliteCandidateStore: ready, path={}", path);
    }

    private void initSchema() {
        String ddlCandidate = "CREATE TABLE IF NOT EXISTS " + CANDIDATE_TABLE + " ("
                + "id TEXT PRIMARY KEY,"
                + "fingerprint TEXT NOT NULL UNIQUE,"
                + "title TEXT,"
                + "content TEXT NOT NULL,"
                + "keywords TEXT,"
                + "category TEXT,"
                + "scope TEXT,"
                + "source_type TEXT,"
                + "evidence TEXT,"
                + "confidence TEXT,"
                + "status TEXT NOT NULL,"
                + "decision TEXT,"
                + "result_memory_id TEXT,"
                + "note TEXT,"
                + "project_key TEXT,"
                + "source_session_id TEXT,"
                + "created_at INTEGER,"
                + "processed_at INTEGER)";
        String ddlLog = "CREATE TABLE IF NOT EXISTS " + LOG_TABLE + " ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "candidate_id TEXT,"
                + "fingerprint TEXT,"
                + "action TEXT,"
                + "memory_id TEXT,"
                + "before_snapshot TEXT,"
                + "note TEXT,"
                + "created_at INTEGER)";
        try (Connection c = open(); Statement st = c.createStatement()) {
            st.execute(ddlCandidate);
            st.execute(ddlLog);
            // 决策日志按候选 id 点查（第三阶段可观测性），普通索引即可
            st.execute("CREATE INDEX IF NOT EXISTS idx_cand_log_cid ON " + LOG_TABLE + "(candidate_id)");
        } catch (SQLException e) {
            throw new IllegalStateException("init candidate schema failed: " + jdbcUrl, e);
        }
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }

    @Override
    public boolean insertIfAbsent(MemoryCandidate candidate) {
        if (candidate == null || candidate.getContent() == null || candidate.getContent().isBlank()) {
            return false;
        }
        if (candidate.getId() == null || candidate.getId().isBlank()) {
            candidate.setId(CommonUtils.generateId());
        }
        if (candidate.getCreatedAt() <= 0) {
            candidate.setCreatedAt(System.currentTimeMillis());
        }
        // INSERT OR IGNORE 命中 fingerprint UNIQUE 冲突即幂等跳过，返回值区分"新插入"
        try (Connection c = open()) {
            return bindCandidate(c.prepareStatement(INSERT_IGNORE_SQL), candidate).executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("insert candidate failed, fingerprint=" + candidate.getFingerprint(), e);
        }
    }

    @Override
    public void save(MemoryCandidate candidate) {
        if (candidate == null || candidate.getId() == null || candidate.getId().isBlank()) {
            return;
        }
        // 先删后插的 upsert：与 SqliteMemoryStore.save 同一手法，规避 INSERT OR REPLACE
        // 触发同表 fingerprint UNIQUE 自冲突的边界
        try (Connection c = open()) {
            try (PreparedStatement del = c.prepareStatement("DELETE FROM " + CANDIDATE_TABLE + " WHERE id = ?")) {
                del.setString(1, candidate.getId());
                del.executeUpdate();
            }
            bindCandidate(c.prepareStatement(INSERT_SQL), candidate).executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("save candidate failed, id=" + candidate.getId(), e);
        }
    }

    @Override
    public MemoryCandidate findByFingerprint(String fingerprint) {
        if (fingerprint == null || fingerprint.isBlank()) {
            return null;
        }
        String sql = "SELECT " + CANDIDATE_COLS + " FROM " + CANDIDATE_TABLE + " WHERE fingerprint = ? LIMIT 1";
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, fingerprint);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapCandidate(rs) : null;
            }
        } catch (SQLException e) {
            log.error("SqliteCandidateStore: findByFingerprint failed, fp={}", fingerprint, e);
            return null;
        }
    }

    @Override
    public List<MemoryCandidate> listPending(int limit) {
        List<MemoryCandidate> result = new ArrayList<>();
        if (limit <= 0) {
            return result;
        }
        String sql = "SELECT " + CANDIDATE_COLS + " FROM " + CANDIDATE_TABLE
                + " WHERE status = 'PENDING' ORDER BY created_at ASC LIMIT ?";
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(mapCandidate(rs));
                }
            }
        } catch (SQLException e) {
            log.error("SqliteCandidateStore: listPending failed", e);
        }
        return result;
    }

    @Override
    public void appendLog(ConsolidationLogEntry entry) {
        if (entry == null || entry.getAction() == null) {
            return;
        }
        String sql = "INSERT INTO " + LOG_TABLE
                + " (candidate_id, fingerprint, action, memory_id, before_snapshot, note, created_at) VALUES (?,?,?,?,?,?,?)";
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, nvl(entry.getCandidateId()));
            ps.setString(2, nvl(entry.getFingerprint()));
            ps.setString(3, entry.getAction().name());
            ps.setString(4, nvl(entry.getMemoryId()));
            ps.setString(5, entry.getBeforeSnapshot());
            ps.setString(6, entry.getNote());
            ps.setLong(7, entry.getCreatedAt() > 0 ? entry.getCreatedAt() : System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            // 日志失败不回滚正式库写入：整理结果已生效，缺一条审计记录不值得牵连主链路
            log.error("SqliteCandidateStore: appendLog failed, candidate={}", entry.getCandidateId(), e);
        }
    }

    @Override
    public List<ConsolidationLogEntry> recentLogs(int limit) {
        List<ConsolidationLogEntry> result = new ArrayList<>();
        if (limit <= 0) {
            return result;
        }
        String sql = "SELECT candidate_id, fingerprint, action, memory_id, before_snapshot, note, created_at"
                + " FROM " + LOG_TABLE + " ORDER BY id DESC LIMIT ?";
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ConsolidationLogEntry e = new ConsolidationLogEntry();
                    e.setCandidateId(rs.getString("candidate_id"));
                    e.setFingerprint(rs.getString("fingerprint"));
                    e.setAction(ConsolidationAction.from(rs.getString("action")));
                    e.setMemoryId(rs.getString("memory_id"));
                    e.setBeforeSnapshot(rs.getString("before_snapshot"));
                    e.setNote(rs.getString("note"));
                    e.setCreatedAt(rs.getLong("created_at"));
                    result.add(e);
                }
            }
        } catch (SQLException e) {
            log.error("SqliteCandidateStore: recentLogs failed", e);
        }
        return result;
    }

    @Override
    public int pruneProcessed(int keepRecent) {
        if (keepRecent <= 0) {
            return 0;
        }
        // 只清已处理的，PENDING 永不删（残留由 drain 兜底负责）
        String sql = "DELETE FROM " + CANDIDATE_TABLE
                + " WHERE status <> 'PENDING' AND id NOT IN ("
                + "SELECT id FROM " + CANDIDATE_TABLE + " WHERE status <> 'PENDING'"
                + " ORDER BY created_at DESC LIMIT ?)";
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, keepRecent);
            return ps.executeUpdate();
        } catch (SQLException e) {
            log.error("SqliteCandidateStore: pruneProcessed failed", e);
            return 0;
        }
    }

    private static PreparedStatement bindCandidate(PreparedStatement ps, MemoryCandidate c) throws SQLException {
        ps.setString(1, c.getId());
        ps.setString(2, nvl(c.getFingerprint()));
        ps.setString(3, c.getTitle());
        ps.setString(4, c.getContent());
        ps.setString(5, joinKeywords(c.getKeywords()));
        ps.setString(6, (c.getCategory() == null ? MemoryCategory.OTHER : c.getCategory()).name());
        ps.setString(7, (c.getScope() == null ? MemoryScope.PROJECT : c.getScope()).name());
        ps.setString(8, (c.getSourceType() == null ? MemoryCandidate.SourceType.RUN_REFLECTION : c.getSourceType()).name());
        ps.setString(9, nvl(c.getEvidence()));
        ps.setString(10, (c.getConfidence() == null ? MemoryCandidate.Confidence.MEDIUM : c.getConfidence()).name());
        ps.setString(11, (c.getStatus() == null ? MemoryCandidate.Status.PENDING : c.getStatus()).name());
        ps.setString(12, c.getDecision() == null ? null : c.getDecision().name());
        ps.setString(13, c.getResultMemoryId());
        ps.setString(14, c.getNote());
        ps.setString(15, nvl(c.getProjectKey()));
        ps.setString(16, nvl(c.getSourceSessionId()));
        ps.setLong(17, c.getCreatedAt());
        ps.setLong(18, c.getProcessedAt());
        return ps;
    }

    private static MemoryCandidate mapCandidate(ResultSet rs) throws SQLException {
        MemoryCandidate c = new MemoryCandidate();
        c.setId(rs.getString("id"));
        c.setFingerprint(rs.getString("fingerprint"));
        c.setTitle(rs.getString("title"));
        c.setContent(rs.getString("content"));
        c.setKeywords(splitKeywords(rs.getString("keywords")));
        c.setCategory(MemoryCategory.from(rs.getString("category")));
        c.setScope(MemoryScope.from(rs.getString("scope")));
        c.setSourceType(MemoryCandidate.SourceType.from(rs.getString("source_type")));
        c.setEvidence(rs.getString("evidence"));
        c.setConfidence(MemoryCandidate.Confidence.from(rs.getString("confidence")));
        c.setStatus(MemoryCandidate.Status.from(rs.getString("status")));
        c.setDecision(ConsolidationAction.from(rs.getString("decision")));
        c.setResultMemoryId(rs.getString("result_memory_id"));
        c.setNote(rs.getString("note"));
        c.setProjectKey(rs.getString("project_key"));
        c.setSourceSessionId(rs.getString("source_session_id"));
        c.setCreatedAt(rs.getLong("created_at"));
        c.setProcessedAt(rs.getLong("processed_at"));
        return c;
    }

    private static String joinKeywords(List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return "";
        }
        return String.join(",", keywords.stream().filter(k -> k != null && !k.isBlank()).toList());
    }

    private static List<String> splitKeywords(String raw) {
        if (raw == null || raw.isBlank()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList());
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    private static void ensureParentDir(String path) {
        try {
            Path p = Path.of(path).toAbsolutePath().getParent();
            if (p != null) {
                Files.createDirectories(p);
            }
        } catch (Exception e) {
            log.error("SqliteCandidateStore: failed to create db parent dir for {}", path, e);
        }
    }

    private static String expandHome(String path) {
        if (path != null && path.startsWith("~")) {
            return Path.of(System.getProperty("user.home"), path.substring(1).replaceFirst("^/", "")).toString();
        }
        return path;
    }
}
