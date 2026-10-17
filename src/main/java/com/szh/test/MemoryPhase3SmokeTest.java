package com.szh.test;

import com.szh.context.dto.MessageItem;
import com.szh.context.dto.SystemMessageItem;
import com.szh.context.dto.UserMessageItem;
import com.szh.memory.GoalStatus;
import com.szh.memory.LongTermMemory;
import com.szh.memory.Memory;
import com.szh.memory.MemoryCategory;
import com.szh.memory.MemoryGoal;
import com.szh.memory.MemoryScope;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * @author demussong
 * @describe L4 长期记忆「第三阶段：画像常驻 / 活动目标 / 检索侧优化 / 可观测性」冒烟测试，覆盖：
 * ① 核心画像：PROFILE+GLOBAL 记忆跨项目常驻，即便本轮无关键词召回也会注入 system prompt；
 * ② 活动目标状态机：ACTIVE→DONE/CANCELLED/SUPERSEDED 流转、终态补 completedAt、常驻注入、
 *    非法入参/不存在 id 一律安全降级（不抛异常）；
 * ③ 组装优先级：画像 &gt; 目标 &gt; 召回 三段按序拼进同一注入块；
 * ④ 短输入查询扩展：本轮输入过短（指代性追问）时用近期对话补全检索词，无近期对话则不扩展；
 * ⑤ 单条正文截断（perItemMaxChars）；⑥ 注入总预算（injectMaxChars）超限截断加省略号；
 * ⑦ 可观测性访问器（total/goalTotal/recentMemories/recentConsolidationLogs/currentProjectKey）
 *    与人工遗忘 forget(id)。
 * <p>
 * 用法：{@code mvn -q compile && java -cp "target/classes:$(mvn -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout)" com.szh.test.MemoryPhase3SmokeTest}
 * @date 2026-10-16
 */
public class MemoryPhase3SmokeTest {

    private static int passed = 0;
    private static int failed = 0;

    private static Path tmpRoot;

    /** 注入块拼在基准 system 之后，用固定前缀便于切出注入段做长度断言 */
    private static final String BASE = "BASE-SYSTEM";

    public static void main(String[] args) throws Exception {
        try {
            setUp();
            LongTermMemory ltm = LongTermMemory.get();
            check(ltm.isActive(), "store 就绪");
            check(ltm.isGoalsAvailable(), "② 活动目标层就绪");

            profileResident(ltm);
            goalLifecycle(ltm);
            combinedPriorityOrder(ltm);
            queryExpansion(ltm);
            perItemClipping(ltm);
            observationAndForget(ltm);
            // 最后跑：会灌大量画像条目，放最前会污染前面按字符/条数断言的用例
            injectBudgetTruncation(ltm);

            System.out.println();
            System.out.println("========================================");
            System.out.println(" MemoryPhase3SmokeTest: PASS=" + passed + " FAIL=" + failed);
            System.out.println("========================================");
            if (failed > 0) {
                System.exit(1);
            }
        } finally {
            tearDown();
        }
    }

    // ---------------- ① 画像常驻 ----------------

    private static void profileResident(LongTermMemory ltm) {
        String id = ltm.remember(profileMem("pfk77 用户称呼",
                "pfk77 用户希望被称为 D 先生，回复默认中文"));
        check(id != null, "① PROFILE 记忆写入成功");
        check(containsToken(ltm.profileEntries(), "pfk77"), "① profileEntries 聚合到画像条目");
        // query=null：不做关键词召回，画像仍应常驻注入
        String block = inject(ltm, null, null, null);
        check(block.contains("核心个人画像") && block.contains("pfk77"), "① 无召回时画像仍常驻注入");
    }

    // ---------------- ② 活动目标状态机 + 降级 ----------------

    private static void goalLifecycle(LongTermMemory ltm) {
        String id = ltm.addGoal("gok99 上线前压测", "gok99 生产上线前完成一轮压测", "sess-p3g");
        check(id != null, "② addGoal 返回 id");
        check(containsGoalId(ltm.listActiveGoals(20), id), "② 新目标处于 ACTIVE 列表");
        String block = inject(ltm, null, null, null);
        check(block.contains("进行中的目标") && block.contains("gok99"), "② 进行中目标常驻注入");

        check(ltm.setGoalStatus(id, GoalStatus.DONE), "② 置 DONE 成功");
        check(!containsGoalId(ltm.listActiveGoals(20), id), "② DONE 后不在进行中列表");
        MemoryGoal g = findGoalById(ltm.listRecentGoals(50), id);
        check(g != null && g.getStatus() == GoalStatus.DONE && g.getCompletedAt() > 0,
                "② 终态记录 completedAt 时间戳");

        String id2 = ltm.addGoal("gck99b 待取消", "gck99b 计划做但后来放弃的目标", "sess-p3g");
        check(ltm.setGoalStatus(id2, GoalStatus.CANCELLED), "② cancel 流转生效");
        check(!containsGoalId(ltm.listActiveGoals(20), id2), "② CANCELLED 后不在进行中列表");

        check(ltm.deleteGoal(id), "② deleteGoal 物理删除");
        check(findGoalById(ltm.listRecentGoals(50), id) == null, "② 删除后 recent 也查不到");

        // 安全降级：非法入参/不存在 id 不抛异常
        check(ltm.addGoal("空正文目标", "   ", "sess-p3g") == null, "② 空正文 addGoal 返回 null");
        check(!ltm.updateGoal("no-such-id", null, "x"), "② 不存在 id update 返回 false");
        check(!ltm.setGoalStatus("no-such-id", GoalStatus.DONE), "② 不存在 id setStatus 返回 false");
        check(!ltm.deleteGoal("no-such-id"), "② 不存在 id delete 返回 false");
    }

    // ---------------- ③ 组装优先级：画像 > 目标 > 召回 ----------------

    private static void combinedPriorityOrder(LongTermMemory ltm) {
        ltm.addGoal("ock95 收尾文档", "ock95 补齐第三阶段设计文档", "sess-p3c");
        ltm.remember(projectMem("rck93 数据库选型",
                "rck93 项目数据库采用 MySQL 存储业务主数据", "sess-p3c"));
        String block = inject(ltm, "rck93", "sess-p3c", null);
        int iProfile = block.indexOf("核心个人画像");
        int iGoals = block.indexOf("进行中的目标");
        int iRecall = block.indexOf("rck93 项目数据库");
        check(iProfile >= 0 && iGoals > iProfile && iRecall > iGoals,
                "③ 注入块按 画像>目标>召回 顺序拼接");
    }

    // ---------------- ④ 短输入查询扩展 ----------------

    private static void queryExpansion(LongTermMemory ltm) {
        ltm.remember(projectMem("qek88 缓存话题", "qek88 项目正在讨论缓存击穿的解决方案", "sess-p3q"));
        List<MessageItem> tail = new ArrayList<>();
        tail.add(new SystemMessageItem("sys"));
        tail.add(new UserMessageItem("qek88 之前聊过缓存击穿这个话题"));
        // 本轮输入「为什么」只有 3 字，靠近期对话补全出 qek88 才召得到
        String expanded = inject(ltm, "为什么", "sess-p3q", tail);
        check(expanded.contains("qek88 项目正在讨论"), "④ 短输入经近期对话扩展后召回成功");
        // 无近期对话时不扩展：同一短输入应召回落空
        String raw = inject(ltm, "为什么", "sess-p3q", null);
        check(!raw.contains("qek88 项目正在讨论"), "④ 无近期对话则不扩展、召回落空");
    }

    // ---------------- ⑤ 单条正文截断 ----------------

    private static void perItemClipping(LongTermMemory ltm) {
        String longBody = "clpk81 这条记忆的正文特意写得很长很长，用来验证注入时按 perItemMaxChars 截断，"
                + "重复填充若干内容直到明显超过六十个字符的预算上限，末尾放一个标记 ENDMARK-clpk81-tail";
        ltm.remember(projectMem("clpk81 长正文", longBody, "sess-p3k"));
        String block = inject(ltm, "clpk81", "sess-p3k", null);
        check(block.contains("clpk81 这条记忆的正文") && block.contains("…")
                        && !block.contains("ENDMARK-clpk81-tail"),
                "⑤ 超 perItemMaxChars 的召回正文被截断加省略号");
    }

    // ---------------- ⑥ 注入总预算截断 ----------------

    private static void injectBudgetTruncation(LongTermMemory ltm) {
        // 灌入大量画像，让画像段本身远超 injectMaxChars，验证总预算硬约束
        for (int i = 0; i < 10; i++) {
            ltm.remember(profileMem("budt9p" + i,
                    "budt9p" + i + " 这是一条用来撑满注入预算的常驻画像条目，内容稍长一些以便更快触顶"));
        }
        String full = inject(ltm, null, null, null);
        String block = full.startsWith(BASE) ? full.substring(BASE.length()).stripLeading() : full;
        check(block.length() <= 405, "⑥ 注入块受 injectMaxChars 预算硬约束（长度≈400）");
        check(block.endsWith("…"), "⑥ 超预算段落以省略号收尾");
    }

    // ---------------- ⑦ 观测访问器 + 人工遗忘 ----------------

    private static void observationAndForget(LongTermMemory ltm) {
        check(ltm.total() > 0, "⑦ total 统计正式记忆条数");
        check(ltm.goalTotal() > 0, "⑦ goalTotal 统计目标条数");
        check(!ltm.recentMemories(5).isEmpty(), "⑦ recentMemories 返回最近记忆");
        check(!ltm.recentConsolidationLogs(5).isEmpty(), "⑦ recentConsolidationLogs 返回整理决策日志");
        check(realWorkspace().equals(ltm.currentProjectKey()), "⑦ currentProjectKey 为规范化真实路径");

        String id = ltm.remember(projectMem("fgk90 待遗忘", "fgk90 这条记忆会立刻被人工遗忘", "sess-p3f"));
        check(!ltm.recall("fgk90", null, 5, "sess-p3f").isEmpty(), "⑦ 遗忘前可召回");
        check(ltm.forget(id), "⑦ forget(id) 成功");
        check(ltm.recall("fgk90", null, 5, "sess-p3f").isEmpty(), "⑦ 遗忘后不再召回");
        check(!ltm.forget("no-such-id"), "⑦ forget 不存在 id 返回 false");
    }

    // ---------------- 公共 ----------------

    private static void setUp() throws Exception {
        tmpRoot = Files.createTempDirectory("ltm-phase3-smoke");
        // 须在 LongTermMemory.get() 前设置：读取侧预算/截断配置在构造期一次性加载
        System.setProperty("memory.enabled", "true");
        System.setProperty("memory.sqlite.path", tmpRoot.resolve("memory.db").toString());
        System.setProperty("project.workspace", tmpRoot.toString());
        System.setProperty("memory.profile.enabled", "true");
        System.setProperty("memory.goals.enabled", "true");
        System.setProperty("memory.retrieval.inject.maxChars", "400");
        System.setProperty("memory.retrieval.perItemMaxChars", "60");
    }

    private static void tearDown() {
        for (String key : List.of("memory.sqlite.path", "project.workspace", "memory.profile.enabled",
                "memory.goals.enabled", "memory.retrieval.inject.maxChars", "memory.retrieval.perItemMaxChars")) {
            System.clearProperty(key);
        }
        try (var s = Files.walk(tmpRoot)) {
            s.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                }
            });
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

    /** 走公开 injectRecall 观察注入结果：返回拼接后的整条 system 文本 */
    private static String inject(LongTermMemory ltm, String query, String sessionId, List<MessageItem> tail) {
        List<MessageItem> ctx = new ArrayList<>();
        ctx.add(new SystemMessageItem(BASE));
        ltm.injectRecall(ctx, query, sessionId, tail);
        return ((SystemMessageItem) ctx.get(0)).getContent();
    }

    private static Memory profileMem(String title, String content) {
        Memory m = new Memory();
        m.setTitle(title);
        m.setContent(content);
        m.setCategory(MemoryCategory.PROFILE);
        m.setScope(MemoryScope.GLOBAL);
        return m;
    }

    private static Memory projectMem(String title, String content, String sessionId) {
        Memory m = new Memory();
        m.setTitle(title);
        m.setContent(content);
        m.setCategory(MemoryCategory.PROJECT_INFO);
        m.setScope(MemoryScope.PROJECT);
        m.setSourceSessionId(sessionId);
        return m;
    }

    private static boolean containsToken(List<Memory> list, String token) {
        for (Memory m : list) {
            if (m.getContent() != null && m.getContent().contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsGoalId(List<MemoryGoal> list, String id) {
        return findGoalById(list, id) != null;
    }

    private static MemoryGoal findGoalById(List<MemoryGoal> list, String id) {
        for (MemoryGoal g : list) {
            if (g.getId().equals(id)) {
                return g;
            }
        }
        return null;
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
