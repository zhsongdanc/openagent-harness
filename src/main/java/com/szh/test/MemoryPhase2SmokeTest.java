package com.szh.test;

import com.szh.context.compaction.Summarizer;
import com.szh.context.dto.MessageItem;
import com.szh.context.dto.UserMessageItem;
import com.szh.memory.ConsolidationAction;
import com.szh.memory.LongTermMemory;
import com.szh.memory.Memory;
import com.szh.memory.MemoryCandidate;
import com.szh.memory.MemoryCategory;
import com.szh.memory.MemoryConsolidator;
import com.szh.memory.MemoryScope;
import com.szh.memory.MemoryStore;
import com.szh.memory.SqliteCandidateStore;
import com.szh.memory.SqliteMemoryStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * @author demussong
 * @describe L4 长期记忆「第二阶段：候选层与整理机制」冒烟测试，覆盖：
 * ① 自动反思通道产出候选（落库、APPLIED 终态）且同指纹重复注入幂等——只正式写一次；
 * ② 低可信自动候选被 DISCARD，不进正式库；显式 remember 不受低可信策略影响；
 * ③ 显式 remember 仍同步返回 id（工具契约不变），且完全重复走指纹幂等回执既有 id；
 * ④ mid 区间启发式：同分类→UPDATE 复用旧 id、旧值归档日志；异分类→ADD 各自保留；
 * ⑤ mid 区间 LLM 仲裁：merge 写回合并文本、supersede 删旧行且旧值在日志可追溯、
 *    输出非法回退启发式；
 * ⑥ PENDING 残留由 awaitPendingReflection 兜底 drain；
 * ⑦ 候选层降级（candidateStore=null）仍正常整理落库（kill switch 语义）。
 * <p>
 * 用法：{@code mvn -q compile && java -cp "target/classes:$(mvn -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout)" com.szh.test.MemoryPhase2SmokeTest}
 * @date 2026-10-16
 */
public class MemoryPhase2SmokeTest {

    private static int passed = 0;
    private static int failed = 0;

    private static Path tmpRoot;
    private static Path dbFile;

    public static void main(String[] args) throws Exception {
        try {
            setUp();
            LongTermMemory ltm = LongTermMemory.get();
            check(ltm.isActive(), "store 就绪");

            candidatePipeline(ltm);
            lowConfidenceDiscard(ltm);
            explicitRememberContract(ltm);
            midBandHeuristic(ltm);
            llmArbitration(ltm);
            pendingDrain(ltm);
            degradedWithoutCandidateStore();
            pruneProcessed();

            System.out.println();
            System.out.println("========================================");
            System.out.println(" MemoryPhase2SmokeTest: PASS=" + passed + " FAIL=" + failed);
            System.out.println("========================================");
            if (failed > 0) {
                System.exit(1);
            }
        } finally {
            tearDown();
        }
    }

    // ---------------- ① 候选管道 + 指纹幂等 ----------------

    private static void candidatePipeline(LongTermMemory ltm) {
        String extraction = "[{\"title\":\"idemptok31 反思通道写入\","
                + "\"content\":\"idemptok31 通过反思通道写入长期记忆的候选由指纹保证幂等\","
                + "\"category\":\"PROJECT_INFO\",\"scope\":\"PROJECT\",\"keywords\":[\"idemptok31\"],"
                + "\"evidence\":\"需求评审中确认\",\"confidence\":\"medium\"}]";
        ltm.reflectAndStore("sess-p2a", transcript("idemptok31 会话内容"), returning(extraction));
        check(!ltm.recall("idemptok31", null, 5, "sess-p2a").isEmpty(), "① 反思候选经整理进入正式库");
        check("APPLIED".equals(queryOne(
                "SELECT status FROM memory_candidates WHERE content LIKE 'idemptok31%' LIMIT 1")),
                "① 候选行落库且终态 APPLIED");
        check("ADD".equals(queryOne(
                "SELECT decision FROM memory_candidates WHERE content LIKE 'idemptok31%' LIMIT 1")),
                "① 决策动作为 ADD（无既有相似项）");

        // 重复注入（模拟抢救与 run 末反思双通道蒸出同一事实）：指纹幂等，只正式写一次
        ltm.reflectAndStore("sess-p2a", transcript("idemptok31 会话内容"), returning(extraction));
        check(count("memory_fts", "content LIKE 'idemptok31%'") == 1, "① 同指纹重复注入不双写正式库");
        check(count("memory_candidates", "content LIKE 'idemptok31%'") == 1, "① 同指纹候选行也只有一条");
    }

    // ---------------- ② 低可信丢弃 ----------------

    private static void lowConfidenceDiscard(LongTermMemory ltm) {
        String extraction = "[{\"title\":\"guesswk99 用户编辑器猜测\","
                + "\"content\":\"guesswk99 用户可能偏好用 vim 编写代码\","
                + "\"category\":\"USER_PREFERENCE\",\"scope\":\"GLOBAL\",\"keywords\":[\"guesswk99\"],"
                + "\"confidence\":\"low\"}]";
        ltm.reflectAndStore("sess-p2b", transcript("guesswk99 闲聊内容"), returning(extraction));
        check(ltm.recall("guesswk99", null, 5, null).isEmpty(), "② 低可信自动候选不进正式库");
        check("DISCARDED".equals(queryOne(
                "SELECT status FROM memory_candidates WHERE content LIKE 'guesswk99 用户可能%' LIMIT 1")),
                "② 候选终态 DISCARDED");
    }

    // ---------------- ③ 显式 remember 契约 ----------------

    private static void explicitRememberContract(LongTermMemory ltm) {
        String id1 = ltm.remember(mem("duplkk27 项目采用单库多表",
                "duplkk27 项目采用 SQLite 单库多表存储元数据", MemoryCategory.PROJECT_INFO, "sess-p2c"));
        check(id1 != null && !id1.isBlank(), "③ remember 同步返回记忆 id（工具契约不变）");
        String id2 = ltm.remember(mem("duplkk27 项目采用单库多表",
                "duplkk27 项目采用 SQLite 单库多表存储元数据", MemoryCategory.PROJECT_INFO, "sess-p2c"));
        check(id1 != null && id1.equals(id2), "③ 完全重复写入幂等回执既有 id");
        check(count("memory_fts", "content LIKE 'duplkk27%'") == 1, "③ 完全重复只保留一行");
        check("EXPLICIT".equals(queryOne(
                "SELECT source_type FROM memory_candidates WHERE content LIKE 'duplkk27%' LIMIT 1")),
                "③ 显式写入也留下 EXPLICIT 候选记录（统一可追溯）");
    }

    // ---------------- ④ mid 区间启发式 ----------------

    private static void midBandHeuristic(LongTermMemory ltm) {
        String seed = "dbtok42 本项目数据库使用 MySQL 存储业务数据";
        String refine = "dbtok42 本项目数据库使用 MySQL 存储业务数据，并启用了读写分离";
        String idA = ltm.remember(mem("dbtok42 项目数据库", seed, MemoryCategory.PROJECT_INFO, "sess-p2d"));
        check(idA != null, "④ 种子记忆写入");
        // 同分类的细化：mid 档 → UPDATE（复用旧 id，正文更新）
        String idB = ltm.remember(mem("dbtok42 项目数据库", refine, MemoryCategory.PROJECT_INFO, "sess-p2d"));
        check(idA != null && idA.equals(idB), "④ 同分类相似修正走 UPDATE（复用既有 id）");
        List<Memory> hits = ltm.recall("dbtok42", null, 5, "sess-p2d");
        check(hits.size() == 1 && hits.get(0).getContent().contains("读写分离"), "④ UPDATE 后正文为新表述");
        check(queryOne("SELECT action FROM memory_consolidation_log WHERE action='UPDATE' LIMIT 1") != null
                        && count("memory_consolidation_log", "action='UPDATE' AND before_snapshot LIKE '%MySQL%'") > 0,
                "④ UPDATE 旧值 JSON 已归档整理日志");
        // 异分类：mid 档近似但正文不同的内容各自保留（ADD），不再盲目覆盖
        // （正文完全相同的情况属高档 SKIP/指纹幂等，由③ duplkk27 覆盖）
        String elkSeed = "dbc2kk43 本项目日志采用 ELK 集中收集";
        String elkRefine = "dbc2kk43 本项目日志采用 ELK 集中收集，并配置了告警规则";
        String idElk = ltm.remember(mem("dbc2kk43 日志方案", elkSeed, MemoryCategory.PROJECT_INFO, "sess-p2d"));
        String idC = ltm.remember(mem("dbc2kk43 日志方案", elkRefine, MemoryCategory.DECISION, "sess-p2d"));
        check(idC != null && !idC.equals(idElk), "④ 异分类相似内容走 ADD（不覆盖既有记忆）");
        check(count("memory_fts", "content LIKE 'dbc2kk43%'") == 2, "④ 两条记忆并存");
    }

    // ---------------- ⑤ LLM 仲裁 ----------------

    private static void llmArbitration(LongTermMemory ltm) {
        // merge：互补细化，写回合并文本并复用旧 id
        MemoryStore store = ltm.getStore();
        MemoryConsolidator consolidator = new MemoryConsolidator(store, new SqliteCandidateStore(),
                true, 0.72, 0.92, 8, true);
        String seed = "arbtk55 项目缓存采用 Redis 单层架构";
        String incoming = "arbtk55 项目缓存采用 Redis 单层架构，另加本地二级缓存";
        String id = ltm.remember(mem("arbtk55 缓存架构", seed, MemoryCategory.DECISION, "sess-p2e"));
        MemoryConsolidator.Result r = consolidator.apply(
                candidate("arbtk55 缓存架构", incoming, MemoryCategory.DECISION, "sess-p2e"),
                true, returning("{\"action\":\"merge\",\"title\":\"arbtk55 缓存架构\","
                        + "\"content\":\"arbtk55 项目缓存采用 Redis 加本地二级缓存的分层架构\",\"reason\":\"互补\"}"));
        check(r.action == ConsolidationAction.MERGE && id != null && id.equals(r.memoryId),
                "⑤ 仲裁 merge：复用旧 id");
        check(store.findById(id).getContent().contains("分层架构"), "⑤ 仲裁 merge：合并文本写回");

        // supersede：新事实取代旧事实，旧行删除但日志可追溯
        String seed2 = "svptk59 项目消息队列使用 Kafka";
        String incoming2 = "svptk59 项目消息队列使用 Kafka 承载日志采集链路";
        String id2 = ltm.remember(mem("svptk59 消息队列", seed2, MemoryCategory.PROJECT_INFO, "sess-p2e"));
        MemoryConsolidator.Result r2 = consolidator.apply(
                candidate("svptk59 消息队列", incoming2, MemoryCategory.PROJECT_INFO, "sess-p2e"),
                true, returning("{\"action\":\"supersede\",\"title\":\"svptk59 消息队列\","
                        + "\"content\":\"svptk59 项目消息队列已迁移至 Pulsar\",\"reason\":\"技术栈迁移\"}"));
        check(r2.action == ConsolidationAction.SUPERSEDE && r2.memoryId != null, "⑤ 仲裁 supersede 生效");
        check(id2 == null || store.findById(id2) == null, "⑤ supersede 后旧行从正式库消失");
        check(store.findById(r2.memoryId).getContent().contains("Pulsar"), "⑤ supersede 新行落库");
        check(count("memory_consolidation_log",
                "action='SUPERSEDE' AND before_snapshot LIKE '%svptk59%Kafka%'") > 0,
                "⑤ supersede 旧值在整理日志可追溯");

        // 仲裁输出非法 → 回退启发式（同分类 UPDATE）
        String seed3 = "fbkptk61 项目网关采用 Kong";
        String incoming3 = "fbkptk61 项目网关采用 Kong 并开启了限流插件";
        String id3 = ltm.remember(mem("fbkptk61 网关", seed3, MemoryCategory.DECISION, "sess-p2e"));
        MemoryConsolidator.Result r3 = consolidator.apply(
                candidate("fbkptk61 网关", incoming3, MemoryCategory.DECISION, "sess-p2e"),
                true, returning("我觉得这两条挺相似的"));
        check(id3 != null && id3.equals(r3.memoryId) && store.findById(id3).getContent().contains("限流"),
                "⑤ 仲裁输出非法回退启发式 UPDATE");
    }

    // ---------------- ⑥ PENDING 残留 drain ----------------

    private static void pendingDrain(LongTermMemory ltm) {
        SqliteCandidateStore cs = new SqliteCandidateStore();
        MemoryCandidate pending = candidate("draintok66 残留候选",
                "draintok66 提交失败停留在 PENDING 的候选由退出前 drain 兜底", MemoryCategory.OTHER, "sess-p2f");
        pending.setProjectKey(realWorkspace());
        cs.insertIfAbsent(pending);
        check("PENDING".equals(queryOne(
                "SELECT status FROM memory_candidates WHERE content LIKE 'draintok66%' LIMIT 1")),
                "⑥ 存在 PENDING 残留候选");
        ltm.awaitPendingReflection();
        check(!ltm.recall("draintok66", null, 5, "sess-p2f").isEmpty(), "⑥ drain 后候选落入正式库");
        check("APPLIED".equals(queryOne(
                "SELECT status FROM memory_candidates WHERE content LIKE 'draintok66%' LIMIT 1")),
                "⑥ drain 后候选终态 APPLIED");
    }

    // ---------------- ⑦ 候选层降级（kill switch 语义） ----------------

    private static void degradedWithoutCandidateStore() {
        // candidateStore=null：不持久化候选、不写日志，但语义整理与落库能力保留
        MemoryConsolidator bare = new MemoryConsolidator(new SqliteMemoryStore(), null,
                true, 0.72, 0.92, 8, true);
        MemoryCandidate cand = candidate("killsw91 降级直写",
                "killsw91 候选层关闭时提取结果仍直接过整理器落库", MemoryCategory.OTHER, "sess-p2g");
        cand.setProjectKey(realWorkspace());
        MemoryConsolidator.Result r = bare.apply(cand, false, null);
        check(r.memoryId != null && r.action == ConsolidationAction.ADD, "⑦ 候选层降级仍正常整理落库");
        check(count("memory_candidates", "content LIKE 'killsw91%'") == 0, "⑦ 降级时不写候选记录");
    }

    // ---------------- prune ----------------

    private static void pruneProcessed() {
        new SqliteCandidateStore().pruneProcessed(2);
        check(count("memory_candidates", "status <> 'PENDING'") <= 2, "prune 已处理候选只保留最近 N 条");
    }

    // ---------------- 公共 ----------------

    private static void setUp() throws Exception {
        tmpRoot = Files.createTempDirectory("ltm-phase2-smoke");
        dbFile = tmpRoot.resolve("memory.db");
        // -D 等价的系统属性优先于 application.properties；须在 LongTermMemory.get() 前设置
        System.setProperty("memory.enabled", "true");
        System.setProperty("memory.sqlite.path", dbFile.toString());
        System.setProperty("memory.reflection.enabled", "true");
        System.setProperty("memory.reflection.trigger", "on_compaction");
        System.setProperty("memory.candidate.keepRecent", "50");
        System.setProperty("project.workspace", tmpRoot.toString());
    }

    private static void tearDown() {
        for (String key : List.of("memory.sqlite.path", "memory.reflection.trigger",
                "memory.candidate.keepRecent", "project.workspace")) {
            System.clearProperty(key);
        }
        try {
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

    private static String realWorkspace() {
        try {
            return tmpRoot.toRealPath().toString();
        } catch (Exception e) {
            return tmpRoot.toString();
        }
    }

    private static List<MessageItem> transcript(String... texts) {
        List<MessageItem> list = new ArrayList<>();
        for (String t : texts) {
            list.add(new UserMessageItem(t));
        }
        return list;
    }

    /** 固定回复的假摘要器：反思/仲裁请求都拿到同一 canned 输出（各场景喂不同 JSON） */
    private static Summarizer returning(String output) {
        return messages -> output;
    }

    private static Memory mem(String title, String content, MemoryCategory category, String sessionId) {
        Memory m = new Memory();
        m.setTitle(title);
        m.setContent(content);
        m.setCategory(category);
        m.setScope(MemoryScope.PROJECT);
        m.setSourceSessionId(sessionId);
        return m;
    }

    /** 构造带指纹的自动候选（直连整理器场景用；项目归属与来源会话对齐测试环境） */
    private static MemoryCandidate candidate(String title, String content, MemoryCategory category, String sessionId) {
        MemoryCandidate c = new MemoryCandidate();
        c.setTitle(title);
        c.setContent(content);
        c.setCategory(category);
        c.setScope(MemoryScope.PROJECT);
        c.setSourceType(MemoryCandidate.SourceType.RUN_REFLECTION);
        c.setConfidence(MemoryCandidate.Confidence.MEDIUM);
        c.setSourceSessionId(sessionId);
        c.setFingerprint(MemoryCandidate.computeFingerprint(c));
        return c;
    }

    /** 直查 SQLite：取第一行第一列（候选/日志的内部字段断言用） */
    private static String queryOne(String sql) {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
             Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        } catch (Exception e) {
            throw new RuntimeException("query failed: " + sql, e);
        }
    }

    private static int count(String table, String where) {
        String v = queryOne("SELECT count(*) FROM " + table + " WHERE " + where);
        return v == null ? -1 : Integer.parseInt(v);
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
