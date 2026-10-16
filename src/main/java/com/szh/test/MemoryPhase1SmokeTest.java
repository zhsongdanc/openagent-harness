package com.szh.test;

import com.szh.context.compaction.Summarizer;
import com.szh.context.dto.AssistantMessageItem;
import com.szh.context.dto.MessageItem;
import com.szh.context.dto.SystemMessageItem;
import com.szh.context.dto.UserMessageItem;
import com.szh.memory.LongTermMemory;
import com.szh.memory.Memory;
import com.szh.memory.MemoryExtractor;
import com.szh.memory.MemoryScope;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * @author demussong
 * @describe L4 长期记忆「第一阶段正确性修复」冒烟测试，覆盖四件事：
 * ① MemoryExtractor 截断方向——超预算时优先保留对话尾部（最新内容），早期消息被省略且带显式标记；
 * ② SESSION 作用域真隔离——检索/自动注入/去重都按 source_session_id 划界，其他会话的 SESSION
 *    记忆既召回不到、也不会被跨会话 merge 覆盖，GLOBAL/PROJECT 与不传 sessionId 的旧行为不受影响；
 * ③ collectDiscarded 按对象引用对比压缩前后，同时识别「整条裁掉」（L1）与「截断替换」（L0）两类丢失；
 * ④ 压缩抢救通道——maybeReflectOnRunEnd 收到丢弃消息后独立提交一次蒸馏，输入是丢弃原文而不是压缩后上下文。
 * <p>
 * 用法：{@code mvn -q compile && java -cp "target/classes:$(mvn -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout)" com.szh.test.MemoryPhase1SmokeTest}
 * @date 2026-10-16
 */
public class MemoryPhase1SmokeTest {

    private static int passed = 0;
    private static int failed = 0;

    private static Path tmpRoot;
    private static Path dbFile;

    /** 假摘要器：记录每次收到的抽取请求文本（线程安全，抢救蒸馏在后台线程跑），固定返回空结果 */
    private static final ConcurrentLinkedQueue<String> CAPTURED = new ConcurrentLinkedQueue<>();

    public static void main(String[] args) throws Exception {
        try {
            setUp();
            // ---- ① 截断方向：尾部优先 ----
            truncationKeepsLatest();

            // ---- ② SESSION 作用域按 sessionId 隔离 ----
            sessionScopeIsolation();

            // ---- ③ collectDiscarded 识别两类丢失 ----
            discardedDetection();

            // ---- ④ 压缩抢救通道 ----
            compactionRescue();

            System.out.println();
            System.out.println("========================================");
            System.out.println(" MemoryPhase1SmokeTest: PASS=" + passed + " FAIL=" + failed);
            System.out.println("========================================");
            if (failed > 0) {
                System.exit(1);
            }
        } finally {
            tearDown();
        }
    }

    // ---------------- ① 截断方向 ----------------

    private static void truncationKeepsLatest() {
        // maxInputChars=300，10 条各 ~120 字：旧实现从头拼接会只留下最早几条；新实现必须保住最新一条
        List<MessageItem> transcript = new ArrayList<>();
        transcript.add(new SystemMessageItem("system 指令，不该进入抽取输入"));
        for (int i = 0; i < 10; i++) {
            String pad = "x".repeat(120);
            transcript.add(new UserMessageItem("earliest00 消息" + i + " " + pad));
            if (i == 9) {
                transcript.add(new AssistantMessageItem("latest99 最终纠正：以后先给结论再解释 " + pad));
            }
        }
        CAPTURED.clear();
        new MemoryExtractor(captureSummarizer()).extract("s-extract", transcript);
        String convo = CAPTURED.poll();
        check(convo != null, "抽取器收到了渲染后的对话记录");
        check(convo != null && convo.contains("latest99"), "超预算时保留了最新内容（尾部优先截断）");
        check(convo != null && !convo.contains("earliest00 消息0"), "超预算时被丢弃的早期消息不再挤占输入");
        check(convo != null && convo.contains("被省略"), "被省略的前文带显式标记告知抽取器");

        // 预算内不触发截断：首条也应完整保留
        CAPTURED.clear();
        List<MessageItem> small = List.of(new UserMessageItem("smalltok 短对话一条"));
        new MemoryExtractor(captureSummarizer()).extract("s-extract2", small);
        String smallConvo = CAPTURED.poll();
        check(smallConvo != null && smallConvo.contains("smalltok") && !smallConvo.contains("被省略"),
                "预算内不截断、不加省略标记");
    }

    // ---------------- ② SESSION 隔离 ----------------

    private static void sessionScopeIsolation() {
        LongTermMemory ltm = LongTermMemory.get();
        check(ltm.isActive(), "store 就绪");

        String idA = ltm.remember(sessionMem("sessmem11 会话 A 的临时结论", "sess-A"));
        check(idA != null, "会话 A 写入 SESSION 记忆");
        check(!ltm.recall("sessmem11", null, 5, "sess-A").isEmpty(), "同会话可召回 SESSION 记忆");
        check(ltm.recall("sessmem11", null, 5, "sess-B").isEmpty(), "其他会话检索不到 A 的 SESSION 记忆（不限 scope）");
        check(ltm.recall("sessmem11", MemoryScope.SESSION, 5, "sess-B").isEmpty(), "显式 SESSION 作用域同样按会话隔离");
        check(!ltm.recall("sessmem11").isEmpty(), "不传 sessionId 的旧调用方保持跨会话可见（兼容语义）");

        // 自动注入路径：sess-B 的 prompt 里不得出现 sess-A 的 SESSION 记忆
        List<MessageItem> ctxB = new ArrayList<>(List.of(new SystemMessageItem("BASE")));
        ltm.injectRecall(ctxB, "sessmem11", "sess-B");
        check(((SystemMessageItem) ctxB.get(0)).getContent().equals("BASE"), "injectRecall 不泄漏他会话 SESSION 记忆");
        List<MessageItem> ctxA = new ArrayList<>(List.of(new SystemMessageItem("BASE")));
        ltm.injectRecall(ctxA, "sessmem11", "sess-A");
        check(ctxA.size() == 1 && ((SystemMessageItem) ctxA.get(0)).getContent().contains("sessmem11"),
                "injectRecall 本会话 SESSION 记忆正常注入");

        // GLOBAL / PROJECT 不受会话过滤影响
        ltm.remember(mem("globalmem77 用户偏好先给结论", "globalmem77 该用户偏好技术问题先给结论再解释", MemoryScope.GLOBAL, null));
        check(!ltm.recall("globalmem77", null, 5, "sess-B").isEmpty(), "GLOBAL 记忆不受会话隔离影响");
        ltm.remember(mem("projtok55 项目使用 SQLite", "projtok55 本项目用 SQLite FTS5 存长期记忆", MemoryScope.PROJECT, "sess-A"));
        check(!ltm.recall("projtok55", null, 5, "sess-B").isEmpty(), "PROJECT 记忆不受会话隔离影响");

        // 去重按会话划界：几乎相同的正文，同会话 merge 复用 id，跨会话必须另起新行
        String idA2 = ltm.remember(sessionMem("sessmem11 会话 A 的临时结论", "sess-A"));
        check(idA != null && idA.equals(idA2), "同会话写相似 SESSION 记忆走 merge（复用既有 id）");
        String idB = ltm.remember(sessionMem("sessmem11 会话 A 的临时结论", "sess-B"));
        check(idB != null && !idB.equals(idA), "跨会话写相似 SESSION 记忆不覆盖 A 的行（去重按会话划界）");
    }

    // ---------------- ③ collectDiscarded ----------------

    private static void discardedDetection() {
        MessageItem sys = new SystemMessageItem("sys");
        MessageItem a = new UserMessageItem("aaatok11 被 L1 整条裁掉的历史");
        MessageItem b = new UserMessageItem("bbbbok22 原样保留的尾部");
        MessageItem oldTool = new AssistantMessageItem("ccccck33 被 L0 截断替换的长内容原文");
        MessageItem newTool = new AssistantMessageItem("ccccck33 被 L0 截断替换的长内容");
        List<MessageItem> before = new ArrayList<>(List.of(sys, a, b, oldTool));
        List<MessageItem> after = new ArrayList<>(List.of(sys, newTool, b));

        List<MessageItem> discarded = LongTermMemory.collectDiscarded(before, after);
        check(discarded.size() == 2 && discarded.contains(a) && discarded.contains(oldTool),
                "整条裁掉与截断替换两类丢失都被识别");
        check(!discarded.contains(sys) && !discarded.contains(b), "保留项与 system 不误报");
    }

    // ---------------- ④ 压缩抢救 ----------------

    private static void compactionRescue() {
        LongTermMemory ltm = LongTermMemory.get();
        CAPTURED.clear();
        List<MessageItem> discarded = List.of(new UserMessageItem("rescuetok77 压缩前必须抢救的关键约束"));
        List<MessageItem> compressedContext = List.of(new UserMessageItem("finaltok88 压缩后上下文里的新内容"));
        // on_compaction 策略 + 发生压缩：抢救通道（输入=丢弃原文）与常规反思（输入=压缩后上下文）各跑一次
        ltm.maybeReflectOnRunEnd("sess-rescue", compressedContext, captureSummarizer(), true, discarded);
        ltm.awaitPendingReflection();
        boolean sawRescue = CAPTURED.stream().anyMatch(t -> t.contains("rescuetok77") && !t.contains("finaltok88"));
        boolean sawNormal = CAPTURED.stream().anyMatch(t -> t.contains("finaltok88") && !t.contains("rescuetok77"));
        check(sawRescue, "抢救蒸馏的输入是被丢弃的原文（而非压缩后上下文）");
        check(sawNormal, "常规 run 末反思仍按策略执行（输入=当前上下文）");

        // 未发生压缩：不产生抢救任务，仅剩常规策略判定（on_compaction 未命中则完全不跑）
        CAPTURED.clear();
        ltm.maybeReflectOnRunEnd("sess-none", compressedContext, captureSummarizer(), false, List.of());
        ltm.awaitPendingReflection();
        check(CAPTURED.isEmpty(), "未压缩时 on_compaction 不反思、也不抢救");
    }

    // ---------------- 公共 ----------------

    private static Summarizer captureSummarizer() {
        return messages -> {
            StringBuilder sb = new StringBuilder();
            for (MessageItem m : messages) {
                if (!(m instanceof SystemMessageItem)) {
                    sb.append(m.transfer2prompt());
                }
            }
            CAPTURED.add(sb.toString());
            return "[]";
        };
    }

    private static void setUp() throws Exception {
        tmpRoot = Files.createTempDirectory("ltm-phase1-smoke");
        dbFile = tmpRoot.resolve("memory.db");
        // -D 等价的系统属性优先于 application.properties；须在 LongTermMemory.get() 前设置
        System.setProperty("memory.enabled", "true");
        System.setProperty("memory.sqlite.path", dbFile.toString());
        System.setProperty("memory.reflection.enabled", "true");
        System.setProperty("memory.reflection.trigger", "on_compaction");
        System.setProperty("memory.reflection.maxInputChars", "300");
        System.setProperty("project.workspace", tmpRoot.toString());
    }

    private static void tearDown() {
        for (String key : List.of("memory.sqlite.path", "memory.reflection.trigger",
                "memory.reflection.maxInputChars", "project.workspace")) {
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

    private static Memory mem(String title, String content, MemoryScope scope, String sessionId) {
        Memory m = new Memory();
        m.setTitle(title);
        m.setContent(content);
        m.setScope(scope);
        m.setSourceSessionId(sessionId);
        return m;
    }

    private static Memory sessionMem(String content, String sessionId) {
        return mem(content.substring(0, Math.min(12, content.length())), content, MemoryScope.SESSION, sessionId);
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
