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
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 基于 SQLite FTS5 的长期记忆存储（借鉴主流 harness 的本地嵌入式检索方案）。
 * <p>
 * 设计要点：
 * <ul>
 *   <li><b>单表 FTS5</b>：正文/标题/关键词建全文索引，元数据（id/category/scope/project_key/时间戳）以 UNINDEXED 列存放，
 *       一张虚拟表即为唯一真相源，避免外表 + 触发器的同步复杂度；</li>
 *   <li><b>trigram 分词</b>：默认 {@code tokenize='trigram'}，对中文（CJK）友好——把任意 3 字子串作为词元，
 *       MATCH 退化为子串匹配；可用 {@code memory.sqlite.fts.tokenizer} 切换；</li>
 *   <li><b>BM25 排序</b>：检索用 FTS5 内置 {@code bm25()} 打分，值越小（越负）越相关，ORDER BY 升序即相关度降序；</li>
 *   <li><b>零服务</b>：xerial sqlite-jdbc 内嵌 SQLite 原生库，无需本机单独安装 SQLite 服务。</li>
 * </ul>
 * 连接按操作开合（本地文件、开销小），构造时做幂等建表；驱动缺失/建表失败会抛异常，
 * 由 {@link LongTermMemory} 捕获并优雅降级为“记忆功能关闭”。
 *
 * @author demussong
 * @date 2026/9/21
 */
@Slf4j
public class SqliteMemoryStore implements MemoryStore {

    private static final String PATH_KEY = "memory.sqlite.path";
    private static final String DEFAULT_PATH = "~/.openagent/memory.db";
    private static final String TOKENIZER_KEY = "memory.sqlite.fts.tokenizer";
    private static final String DEFAULT_TOKENIZER = "trigram";

    /** FTS5 虚拟表名，bm25() 与 MATCH 都引用它 */
    private static final String TABLE = "memory_fts";

    /** 统一列顺序，供建表/插入/查询复用 */
    private static final String COLS =
            "id, title, content, keywords, category, scope, source_session_id, created_at, updated_at, project_key";

    private static final String INSERT_SQL =
            "INSERT INTO " + TABLE + " (" + COLS + ") VALUES (?,?,?,?,?,?,?,?,?,?)";

    private static final String SELECT_COLS =
            "SELECT id, title, content, keywords, category, scope, source_session_id, created_at, updated_at, project_key";

    private final String jdbcUrl;
    private final boolean trigram;

    public SqliteMemoryStore() {
        String path = expandHome(ConfigUtil.get(PATH_KEY, DEFAULT_PATH));
        ensureParentDir(path);
        this.jdbcUrl = "jdbc:sqlite:" + path;

        String tk = ConfigUtil.get(TOKENIZER_KEY, DEFAULT_TOKENIZER).trim();
        // 白名单校验，防止把非法内容拼进 DDL
        if (!tk.matches("[a-zA-Z0-9_ ]+")) {
            log.warn("SqliteMemoryStore: illegal tokenizer '{}', fallback to {}", tk, DEFAULT_TOKENIZER);
            tk = DEFAULT_TOKENIZER;
        }
        this.trigram = tk.toLowerCase().contains("trigram");

        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("sqlite-jdbc driver not found on classpath", e);
        }
        initSchema(tk);
        log.info("SqliteMemoryStore: ready, path={}, tokenizer={}, count={}", path, tk, count());
    }

    private void initSchema(String tokenizer) {
        String ddl = "CREATE VIRTUAL TABLE IF NOT EXISTS " + TABLE + " USING fts5("
                + fts5Columns(tokenizer)
                + ")";
        try (Connection c = open()) {
            try (Statement st = c.createStatement()) {
                st.execute(ddl);
            }
            // 旧库（9 列）缺项目归属列：FTS5 不支持 ADD COLUMN，需检测到后重建迁移
            if (!hasColumn(c, "project_key")) {
                migrateAddProjectKey(c, tokenizer);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("init FTS5 schema failed: " + jdbcUrl, e);
        }
    }

    /** FTS5 列定义：title/content/keywords 建全文索引，其余（含项目归属 project_key）以 UNINDEXED 存放 */
    private static String fts5Columns(String tokenizer) {
        return "id UNINDEXED, "
                + "title, "
                + "content, "
                + "keywords, "
                + "category UNINDEXED, "
                + "scope UNINDEXED, "
                + "source_session_id UNINDEXED, "
                + "created_at UNINDEXED, "
                + "updated_at UNINDEXED, "
                + "project_key UNINDEXED, "
                + "tokenize = '" + tokenizer + "'";
    }

    /** PRAGMA table_info 检查虚拟表是否已有某列（FTS5 虚拟表同样适用） */
    private static boolean hasColumn(Connection c, String column) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("PRAGMA table_info(" + TABLE + ")")) {
            while (rs.next()) {
                if (column.equals(rs.getString("name"))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 旧库（9 列）补 project_key 的迁移：建新表→拷贝→删旧表→改名，事务包裹保证原子性。
     * 存量行 project_key 置空串，语义为「未归属、跨项目可见」——保守保留不丢弃；
     * 后续被某项目更新命中时（merge 空键取新值）才会收编到该项目名下。
     */
    private void migrateAddProjectKey(Connection c, String tokenizer) throws SQLException {
        String tmp = TABLE + "_v2";
        String legacyCols = "id, title, content, keywords, category, scope, source_session_id, created_at, updated_at";
        c.setAutoCommit(false);
        try (Statement st = c.createStatement()) {
            st.execute("CREATE VIRTUAL TABLE " + tmp + " USING fts5(" + fts5Columns(tokenizer) + ")");
            st.execute("INSERT INTO " + tmp + " (" + legacyCols + ", project_key) SELECT " + legacyCols + ", '' FROM " + TABLE);
            st.execute("DROP TABLE " + TABLE);
            st.execute("ALTER TABLE " + tmp + " RENAME TO " + TABLE);
            c.commit();
            log.info("SqliteMemoryStore: schema migrated, added project_key column (unindexed)");
        } catch (SQLException e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(true);
        }
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }

    @Override
    public void save(Memory memory) {
        if (memory == null || memory.getContent() == null || memory.getContent().isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (memory.getId() == null || memory.getId().isBlank()) {
            memory.setId(CommonUtils.generateId());
        }
        if (memory.getCreatedAt() <= 0) {
            memory.setCreatedAt(now);
        }
        memory.setUpdatedAt(now);

        try (Connection c = open()) {
            deleteById(c, memory.getId());
            try (PreparedStatement ps = c.prepareStatement(INSERT_SQL)) {
                ps.setString(1, memory.getId());
                ps.setString(2, nvl(memory.getTitle()));
                ps.setString(3, memory.getContent());
                ps.setString(4, joinKeywords(memory.getKeywords()));
                ps.setString(5, (memory.getCategory() == null ? MemoryCategory.OTHER : memory.getCategory()).name());
                ps.setString(6, (memory.getScope() == null ? MemoryScope.PROJECT : memory.getScope()).name());
                ps.setString(7, nvl(memory.getSourceSessionId()));
                ps.setString(8, Long.toString(memory.getCreatedAt()));
                ps.setString(9, Long.toString(memory.getUpdatedAt()));
                ps.setString(10, nvl(memory.getProjectKey()));
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            log.error("SqliteMemoryStore: save failed, id={}", memory.getId(), e);
            throw new RuntimeException("save memory failed", e);
        }
    }

    @Override
    public void deleteById(String id) {
        if (id == null || id.isBlank()) {
            return;
        }
        try (Connection c = open()) {
            deleteById(c, id);
        } catch (SQLException e) {
            log.error("SqliteMemoryStore: deleteById failed, id={}", id, e);
        }
    }

    private void deleteById(Connection c, String id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + TABLE + " WHERE id = ?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        }
    }

    @Override
    public Memory findById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        String sql = SELECT_COLS + " FROM " + TABLE + " WHERE id = ? LIMIT 1";
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapRow(rs) : null;
            }
        } catch (SQLException e) {
            log.error("SqliteMemoryStore: findById failed, id={}", id, e);
            return null;
        }
    }

    @Override
    public List<Memory> search(String query, int topK, MemoryScope scope, String projectKey, String sessionId) {
        if (topK <= 0) {
            return new ArrayList<>();
        }
        // 无检索词：按最近召回（可带 scope + 项目/会话可见性）
        if (query == null || query.isBlank()) {
            return queryRecent(scope, topK, projectKey, sessionId);
        }
        // 优先走 FTS5 MATCH（bm25 相关度排序）；trigram 无法匹配的短查询（如 2 字中文词）自动回退 LIKE 子串匹配
        String match = buildMatchExpr(query);
        if (match != null) {
            List<Memory> hits = queryMatch(match, scope, topK, projectKey, sessionId);
            if (!hits.isEmpty()) {
                return hits;
            }
        }
        return queryLike(query, scope, topK, projectKey, sessionId);
    }

    /**
     * 追加项目可见性条件片段（语义见 {@link MemoryStore#search(String, int, MemoryScope, String)}）：
     * projectKey 为空不限项目；GLOBAL 天然跨项目；scope 不限时只放行 GLOBAL + 本项目/未归属行，
     * 防止其他项目的记忆泄进注入召回；scope 显式 PROJECT/SESSION 时限定本项目或未归属行。
     */
    private static void addVisibilityCond(List<String> conds, List<Object> params, MemoryScope scope, String projectKey) {
        if (projectKey == null || projectKey.isBlank() || scope == MemoryScope.GLOBAL) {
            return;
        }
        if (scope == null) {
            conds.add("(scope = 'GLOBAL' OR project_key = ? OR project_key = '')");
        } else {
            conds.add("(project_key = ? OR project_key = '')");
        }
        params.add(projectKey);
    }

    /** 前 N 个参数一律按字符串绑定（均为文本条件），末位固定是 LIMIT */
    private static void bindParams(PreparedStatement ps, List<Object> params, int limit) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            ps.setString(i + 1, String.valueOf(params.get(i)));
        }
        ps.setInt(params.size() + 1, limit);
    }

    /**
     * 追加会话可见性条件：SESSION 作用域是「仅当前会话可见」的真隔离边界，而不只是分类标签——
     * 检索时必须按 source_session_id 过滤，否则同项目其他会话的 SESSION 记忆会泄进 prompt。
     * <p>sessionId 为空时不做会话过滤（跨会话管理操作/旧调用方）；未归属
     * （source_session_id 为空串）的历史 SESSION 行按「跨会话可见」保守处理，与 project_key 遗留行同一策略。
     */
    private static void addSessionCond(List<String> conds, List<Object> params, MemoryScope scope, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        if (scope == MemoryScope.SESSION) {
            conds.add("(source_session_id = ? OR source_session_id = '')");
            params.add(sessionId);
        } else if (scope == null) {
            // 混合检索：非 SESSION 行不受影响，SESSION 行仅本会话或遗留行放行
            conds.add("(scope <> 'SESSION' OR source_session_id = ? OR source_session_id = '')");
            params.add(sessionId);
        }
    }

    private List<Memory> queryMatch(String match, MemoryScope scope, int topK, String projectKey, String sessionId) {
        List<String> conds = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        conds.add(TABLE + " MATCH ?");
        params.add(match);
        if (scope != null) {
            conds.add("scope = ?");
            params.add(scope.name());
        }
        addVisibilityCond(conds, params, scope, projectKey);
        addSessionCond(conds, params, scope, sessionId);
        // bm25 值越小越相关，升序即相关度降序
        String sql = SELECT_COLS + ", bm25(" + TABLE + ") AS score FROM " + TABLE
                + " WHERE " + String.join(" AND ", conds) + " ORDER BY score LIMIT ?";
        List<Memory> result = new ArrayList<>();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(sql)) {
            bindParams(ps, params, topK);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            log.error("SqliteMemoryStore: MATCH search failed, match={}", match, e);
        }
        return result;
    }

    /**
     * LIKE 子串兜底检索：处理 trigram 无法匹配的短查询（如 2 字中文词），
     * 命中 content/title/keywords 任一即算匹配，按更新时间倒序。
     * trigram 分词器下 SQLite 会对 &gt;=3 字的 LIKE 子串走索引优化。
     */
    private List<Memory> queryLike(String query, MemoryScope scope, int topK, String projectKey, String sessionId) {
        String like = "%" + escapeLike(query.trim()) + "%";
        List<String> conds = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        conds.add("(content LIKE ? ESCAPE '\\' OR title LIKE ? ESCAPE '\\' OR keywords LIKE ? ESCAPE '\\')");
        params.add(like);
        params.add(like);
        params.add(like);
        if (scope != null) {
            conds.add("scope = ?");
            params.add(scope.name());
        }
        addVisibilityCond(conds, params, scope, projectKey);
        addSessionCond(conds, params, scope, sessionId);
        String sql = SELECT_COLS + " FROM " + TABLE
                + " WHERE " + String.join(" AND ", conds)
                + " ORDER BY CAST(updated_at AS INTEGER) DESC LIMIT ?";
        List<Memory> result = new ArrayList<>();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(sql)) {
            bindParams(ps, params, topK);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            log.error("SqliteMemoryStore: LIKE search failed, query={}", query, e);
        }
        return result;
    }

    @Override
    public List<Memory> listRecent(int limit) {
        return queryRecent(null, limit, null, null);
    }

    /**
     * 画像常驻聚合用：按分类（可选作用域）拉最近若干条。分类是 UNINDEXED 列，直接等值过滤，
     * 走普通 SELECT 而非 FTS MATCH；不做项目/会话可见性过滤（画像约定 scope=GLOBAL，跨项目可见）。
     */
    @Override
    public List<Memory> listByCategory(MemoryCategory category, MemoryScope scope, int limit) {
        List<Memory> result = new ArrayList<>();
        if (limit <= 0) {
            return result;
        }
        List<String> conds = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        if (category != null) {
            conds.add("category = ?");
            params.add(category.name());
        }
        if (scope != null) {
            conds.add("scope = ?");
            params.add(scope.name());
        }
        StringBuilder sql = new StringBuilder(SELECT_COLS).append(" FROM ").append(TABLE);
        if (!conds.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", conds));
        }
        sql.append(" ORDER BY CAST(updated_at AS INTEGER) DESC LIMIT ?");
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(sql.toString())) {
            bindParams(ps, params, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            log.error("SqliteMemoryStore: listByCategory failed, category={}", category, e);
        }
        return result;
    }

    private List<Memory> queryRecent(MemoryScope scope, int limit, String projectKey, String sessionId) {
        List<Memory> result = new ArrayList<>();
        if (limit <= 0) {
            return result;
        }
        List<String> conds = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        if (scope != null) {
            conds.add("scope = ?");
            params.add(scope.name());
        }
        addVisibilityCond(conds, params, scope, projectKey);
        addSessionCond(conds, params, scope, sessionId);
        StringBuilder sql = new StringBuilder(SELECT_COLS).append(" FROM ").append(TABLE);
        if (!conds.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", conds));
        }
        sql.append(" ORDER BY CAST(updated_at AS INTEGER) DESC LIMIT ?");
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(sql.toString())) {
            bindParams(ps, params, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            log.error("SqliteMemoryStore: listRecent failed", e);
        }
        return result;
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    @Override
    public int count() {
        String sql = "SELECT count(*) FROM " + TABLE;
        try (Connection c = open(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            // 建表前调用（如构造日志）可能命中此分支，降级为 0 而非抛异常
            log.warn("SqliteMemoryStore: count failed: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * 构造 FTS5 MATCH 表达式：按空白切词，每个词元加引号做短语匹配（trigram 下即子串匹配），
     * 用 OR 连接。这样既能规避 FTS5 语法特殊字符导致的报错，又对中英文都友好。
     * trigram 要求词元至少 3 字符，过短词元直接丢弃；全部无效时返回 null（触发兜底召回）。
     */
    private String buildMatchExpr(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        int minLen = trigram ? 3 : 1;
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        for (String part : query.trim().split("\\s+")) {
            // 仅保留字母/数字/下划线（\p{L} 含中日韩文字），剔除 FTS5 语法字符
            String cleaned = part.replaceAll("[^\\p{L}\\p{N}_]", "");
            if (cleaned.length() < minLen) {
                continue;
            }
            terms.add("\"" + cleaned.replace("\"", "\"\"") + "\"");
        }
        if (terms.isEmpty()) {
            return null;
        }
        return String.join(" OR ", terms);
    }

    private Memory mapRow(ResultSet rs) throws SQLException {
        Memory m = new Memory();
        m.setId(rs.getString("id"));
        m.setTitle(rs.getString("title"));
        m.setContent(rs.getString("content"));
        m.setKeywords(splitKeywords(rs.getString("keywords")));
        m.setCategory(MemoryCategory.from(rs.getString("category")));
        m.setScope(MemoryScope.from(rs.getString("scope")));
        m.setSourceSessionId(rs.getString("source_session_id"));
        m.setProjectKey(rs.getString("project_key"));
        m.setCreatedAt(parseLong(rs.getString("created_at")));
        m.setUpdatedAt(parseLong(rs.getString("updated_at")));
        return m;
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
        return new ArrayList<>(Arrays.stream(raw.split(",")).map(String::trim)
                .filter(s -> !s.isEmpty()).toList());
    }

    private static long parseLong(String s) {
        try {
            return s == null ? 0L : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
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
            log.error("SqliteMemoryStore: failed to create db parent dir for {}", path, e);
        }
    }

    private static String expandHome(String path) {
        if (path != null && path.startsWith("~")) {
            return Path.of(System.getProperty("user.home"), path.substring(1).replaceFirst("^/", "")).toString();
        }
        return path;
    }
}
