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
import java.util.List;

/**
 * 基于 SQLite 的活动目标存储（第三阶段）：与正式库/候选层共用同一个 {@code ~/.openagent/memory.db}，
 * 建的是<b>普通表</b> {@code memory_goals}（非 FTS5）——目标按状态与项目点查、按时间排序，无需全文索引，
 * 且完全避开 {@code memory_fts} 的整表重建迁移。
 * <p>构造即幂等建表；驱动缺失/建表失败抛异常，由 {@link LongTermMemory} 捕获后仅关闭目标层，主链路不受影响。
 * 连接按操作开合，与 {@link SqliteCandidateStore} 同一策略。
 *
 * @author demussong
 * @date 2026-10-16
 */
@Slf4j
public class SqliteGoalStore implements GoalStore {

    private static final String TABLE = "memory_goals";

    private static final String COLS = "id, title, content, status, project_key, source_session_id,"
            + " created_at, updated_at, completed_at";

    private static final String INSERT_SQL = "INSERT INTO " + TABLE + " (" + COLS + ") VALUES (?,?,?,?,?,?,?,?,?)";

    private final String jdbcUrl;

    public SqliteGoalStore() {
        // 与正式记忆库/候选层共用同一 db 文件（memory.sqlite.path），避免多一套路径配置
        String path = expandHome(ConfigUtil.get("memory.sqlite.path", "~/.openagent/memory.db"));
        ensureParentDir(path);
        this.jdbcUrl = "jdbc:sqlite:" + path;
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("sqlite-jdbc driver not found on classpath", e);
        }
        initSchema();
        log.info("SqliteGoalStore: ready, path={}", path);
    }

    private void initSchema() {
        String ddl = "CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                + "id TEXT PRIMARY KEY,"
                + "title TEXT,"
                + "content TEXT NOT NULL,"
                + "status TEXT NOT NULL,"
                + "project_key TEXT,"
                + "source_session_id TEXT,"
                + "created_at INTEGER,"
                + "updated_at INTEGER,"
                + "completed_at INTEGER)";
        try (Connection c = open(); Statement st = c.createStatement()) {
            st.execute(ddl);
            // 常驻注入按「状态 + 项目」过滤并时间排序，普通索引即可
            st.execute("CREATE INDEX IF NOT EXISTS idx_goal_status ON " + TABLE + "(status, updated_at)");
        } catch (SQLException e) {
            throw new IllegalStateException("init goal schema failed: " + jdbcUrl, e);
        }
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }

    @Override
    public void save(MemoryGoal goal) {
        if (goal == null || goal.getContent() == null || goal.getContent().isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (goal.getId() == null || goal.getId().isBlank()) {
            goal.setId(CommonUtils.generateId());
        }
        if (goal.getCreatedAt() <= 0) {
            goal.setCreatedAt(now);
        }
        if (goal.getStatus() == null) {
            goal.setStatus(GoalStatus.ACTIVE);
        }
        // 进入终态却未记完成时间：补一个，避免 completed_at 永远为 0
        if (goal.getStatus().isTerminal() && goal.getCompletedAt() <= 0) {
            goal.setCompletedAt(now);
        }
        goal.setUpdatedAt(now);
        // 先删后插的 upsert：与候选/正式库同一手法
        try (Connection c = open()) {
            try (PreparedStatement del = c.prepareStatement("DELETE FROM " + TABLE + " WHERE id = ?")) {
                del.setString(1, goal.getId());
                del.executeUpdate();
            }
            bind(c.prepareStatement(INSERT_SQL), goal).executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("save goal failed, id=" + goal.getId(), e);
        }
    }

    @Override
    public void deleteById(String id) {
        if (id == null || id.isBlank()) {
            return;
        }
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement("DELETE FROM " + TABLE + " WHERE id = ?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("SqliteGoalStore: deleteById failed, id={}", id, e);
        }
    }

    @Override
    public MemoryGoal findById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        String sql = "SELECT " + COLS + " FROM " + TABLE + " WHERE id = ? LIMIT 1";
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        } catch (SQLException e) {
            log.error("SqliteGoalStore: findById failed, id={}", id, e);
            return null;
        }
    }

    @Override
    public List<MemoryGoal> listActive(String projectKey, int limit) {
        return list(projectKey, "status = 'ACTIVE'", limit);
    }

    @Override
    public List<MemoryGoal> listRecent(String projectKey, int limit) {
        return list(projectKey, "1=1", limit);
    }

    private List<MemoryGoal> list(String projectKey, String statusCond, int limit) {
        List<MemoryGoal> result = new ArrayList<>();
        if (limit <= 0) {
            return result;
        }
        // 项目可见：GLOBAL 语义的目标无 project_key 之分，仅按「本项目或空键遗留」过滤，与 Memory 一致
        boolean projectFilter = projectKey != null && !projectKey.isBlank();
        String sql = "SELECT " + COLS + " FROM " + TABLE
                + " WHERE " + statusCond
                + (projectFilter ? " AND (project_key = ? OR project_key IS NULL OR project_key = '')" : "")
                + " ORDER BY updated_at DESC LIMIT ?";
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(sql)) {
            int idx = 1;
            if (projectFilter) {
                ps.setString(idx++, projectKey);
            }
            ps.setInt(idx, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(map(rs));
                }
            }
        } catch (SQLException e) {
            log.error("SqliteGoalStore: list failed (cond={})", statusCond, e);
        }
        return result;
    }

    @Override
    public int count() {
        try (Connection c = open(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + TABLE)) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            log.error("SqliteGoalStore: count failed", e);
            return 0;
        }
    }

    private static PreparedStatement bind(PreparedStatement ps, MemoryGoal g) throws SQLException {
        ps.setString(1, g.getId());
        ps.setString(2, g.getTitle());
        ps.setString(3, g.getContent());
        ps.setString(4, (g.getStatus() == null ? GoalStatus.ACTIVE : g.getStatus()).name());
        ps.setString(5, nvl(g.getProjectKey()));
        ps.setString(6, nvl(g.getSourceSessionId()));
        ps.setLong(7, g.getCreatedAt());
        ps.setLong(8, g.getUpdatedAt());
        ps.setLong(9, g.getCompletedAt());
        return ps;
    }

    private static MemoryGoal map(ResultSet rs) throws SQLException {
        MemoryGoal g = new MemoryGoal();
        g.setId(rs.getString("id"));
        g.setTitle(rs.getString("title"));
        g.setContent(rs.getString("content"));
        g.setStatus(GoalStatus.from(rs.getString("status")));
        g.setProjectKey(rs.getString("project_key"));
        g.setSourceSessionId(rs.getString("source_session_id"));
        g.setCreatedAt(rs.getLong("created_at"));
        g.setUpdatedAt(rs.getLong("updated_at"));
        g.setCompletedAt(rs.getLong("completed_at"));
        return g;
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
            log.error("SqliteGoalStore: failed to create db parent dir for {}", path, e);
        }
    }

    private static String expandHome(String path) {
        if (path != null && path.startsWith("~")) {
            return Path.of(System.getProperty("user.home"), path.substring(1).replaceFirst("^/", "")).toString();
        }
        return path;
    }
}
