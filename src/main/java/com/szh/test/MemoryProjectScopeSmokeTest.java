package com.szh.test;

import com.szh.memory.LongTermMemory;
import com.szh.memory.Memory;
import com.szh.memory.MemoryScope;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;

/**
 * @author demussong
 * @describe L4 长期记忆「项目归属隔离」冒烟测试：修复 PROJECT 记忆跨仓库串扰（单一全局库、
 * 无项目标识列导致的读泄漏 / 去重覆盖 / 容量挤兑）。在临时目录里搭出两个假项目，覆盖
 * 「旧 9 列库迁移补 project_key → 未归属遗留行跨项目可见 → PROJECT 记忆只在归属项目可见 →
 * GLOBAL 天然跨项目 → 去重不越项目 merge 覆盖 → 容量淘汰按可见集分桶、不挤掉他项目行」。
 * <p>
 * 用法：{@code mvn -q compile && java -cp "target/classes:$(mvn -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout)" com.szh.test.MemoryProjectScopeSmokeTest}
 * @date 2026-10-15
 */
public class MemoryProjectScopeSmokeTest {

    private static int passed = 0;
    private static int failed = 0;

    private static Path tmpRoot;
    private static Path projA;
    private static Path projB;
    private static Path dbFile;

    public static void main(String[] args) throws Exception {
        try {
            setUp();
            LongTermMemory ltm = LongTermMemory.get();
            check(ltm.isActive(), "store 就绪（含旧库迁移）");

            // ---- 迁移与遗留行 ----
            workspace(projA);
            check(!ltm.recall("legacymem01").isEmpty(), "迁移后遗留行可按内容召回");
            check(ltm.recall("legacymem01").get(0).getContent().contains("旧记忆"),
                    "遗留行正文完整保留、project_key 为空串");

            // ---- PROJECT 记忆写入与跨项目读隔离 ----
            String idAlpha = ltm.remember(mem("构建约定 alphamem42", "alphamem42 项目用 make 构建（alpha 原版标记）", MemoryScope.PROJECT));
            check(idAlpha != null, "项目 A 写入 PROJECT 记忆");
            workspace(projB);
            check(ltm.recall("alphamem42").isEmpty(), "项目 B 召回不到 A 的 PROJECT 记忆（读隔离）");
            check(ltm.recall("legacymem01").size() > 0, "未归属遗留行在任何项目仍可见");
            String idBeta = ltm.remember(mem("部署约定 betamem99", "betamem99 服务用 docker compose 部署", MemoryScope.PROJECT));
            check(idBeta != null && !idBeta.equals(idAlpha), "项目 B 写入自己的 PROJECT 记忆");
            workspace(projA);
            check(ltm.recall("betamem99").isEmpty(), "项目 A 召回不到 B 的 PROJECT 记忆（双向隔离）");

            // ---- GLOBAL 跨项目可见 ----
            workspace(projB);
            ltm.remember(mem("用户偏好 globalmem77", "globalmem77 该用户偏好中文注释", MemoryScope.GLOBAL));
            workspace(projA);
            check(!ltm.recall("globalmem77").isEmpty(), "GLOBAL 记忆天然跨项目可见");

            // ---- 去重不越项目 merge 覆盖 ----
            workspace(projA);
            ltm.remember(mem("规范标记 gkx7", "gkx7 代码遵循统一规范（alpha 原版标记）", MemoryScope.PROJECT));
            workspace(projB);
            ltm.remember(mem("规范标记 gkx7", "gkx7 代码遵循统一规范（beta 变体标记）", MemoryScope.PROJECT));
            boolean betaSawOwn = ltm.recall("gkx7").stream().anyMatch(m -> m.getContent().contains("beta 变体标记"));
            workspace(projA);
            boolean aClean = ltm.recall("gkx7").stream().anyMatch(m -> m.getContent().contains("alpha 原版标记"))
                    && ltm.recall("gkx7").stream().noneMatch(m -> m.getContent().contains("beta 变体标记"));
            check(betaSawOwn && aClean, "B 写相似记忆未覆盖 A 的原行（去重隔离）");

            // ---- 容量淘汰按可见集分桶 ----
            // memory.capacity.max=5：从 B 连写多条把「B 可见集」推过上限。B 淘汰自己的旧行属正常行为，
            // 关键是 A 的 PROJECT 行不得被挤掉（旧逻辑全库计数时 A 的旧行会首先中招）
            workspace(projB);
            for (int i = 1; i <= 4; i++) {
                ltm.remember(mem("压测条目 evictprobe" + i, "evictprobe" + i + " 用于触发 B 侧容量淘汰的填充记忆内容"));
            }
            check(ltm.getStore().findById(idAlpha) != null, "B 侧超限淘汰未挤掉 A 的记忆（分桶生效）");
            workspace(projA);
            check(!ltm.recall("alphamem42").isEmpty(), "淘汰后 A 的项目记忆仍可正常召回");

            System.out.println();
            System.out.println("========================================");
            System.out.println(" MemoryProjectScopeSmokeTest: PASS=" + passed + " FAIL=" + failed);
            System.out.println("========================================");
            if (failed > 0) {
                System.exit(1);
            }
        } finally {
            tearDown();
        }
    }

    private static void setUp() throws Exception {
        tmpRoot = Files.createTempDirectory("ltm-project-smoke");
        projA = Files.createDirectories(tmpRoot.resolve("repoA"));
        projB = Files.createDirectories(tmpRoot.resolve("repoB"));
        dbFile = tmpRoot.resolve("memory.db");

        // 先用「旧 9 列」DDL 建表并写入一条遗留记忆，验证 SqliteMemoryStore 的补列迁移
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
             Statement st = c.createStatement()) {
            st.execute("CREATE VIRTUAL TABLE memory_fts USING fts5("
                    + "id UNINDEXED, title, content, keywords, category UNINDEXED, scope UNINDEXED, "
                    + "source_session_id UNINDEXED, created_at UNINDEXED, updated_at UNINDEXED, "
                    + "tokenize = 'trigram')");
        }
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO memory_fts (id, title, content, keywords, category, scope, "
                             + "source_session_id, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, "legacy-0001");
            ps.setString(2, "历史记忆");
            ps.setString(3, "legacymem01 迁移前就存在的旧记忆，不会丢失");
            ps.setString(4, "");
            ps.setString(5, "OTHER");
            ps.setString(6, "PROJECT");
            ps.setString(7, "");
            long now = System.currentTimeMillis() - 86400000L;
            ps.setString(8, Long.toString(now));
            ps.setString(9, Long.toString(now));
            ps.executeUpdate();
        }

        // -D 等价的系统属性优先于 application.properties；容量上限调小以覆盖淘汰
        System.setProperty("memory.enabled", "true");
        System.setProperty("memory.sqlite.path", dbFile.toString());
        System.setProperty("memory.reflection.enabled", "false");
        System.setProperty("memory.capacity.max", "5");
    }

    private static void tearDown() {
        try {
            System.clearProperty("memory.sqlite.path");
            System.clearProperty("memory.capacity.max");
            if (tmpRoot != null) {
                try (var s = Files.walk(tmpRoot)) {
                    s.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (Exception ignored) {
                        }
                    });
                }
            }
        } catch (Exception ignored) {
        }
    }

    /** 切换「当前项目」：LongTermMemory 每次调用实时解析 project.workspace，单例无需重建 */
    private static void workspace(Path dir) {
        System.setProperty("project.workspace", dir.toString());
    }

    private static Memory mem(String title, String content) {
        return mem(title, content, MemoryScope.PROJECT);
    }

    private static Memory mem(String title, String content, MemoryScope scope) {
        Memory m = new Memory();
        m.setTitle(title);
        m.setContent(content);
        m.setScope(scope);
        return m;
    }

    private static void check(boolean ok, String name) {
        if (ok) {
            passed++;
            System.out.println("  [PASS] " + name);
        } else {
            failed++;
            System.out.println("  [FAIL] " + name);
        }
    }
}
