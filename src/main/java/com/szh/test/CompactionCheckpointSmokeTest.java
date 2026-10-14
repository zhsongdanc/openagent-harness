package com.szh.test;

import com.szh.agent.AgentState;
import com.szh.context.compaction.CompactionResult;
import com.szh.context.compaction.LlmCompactor;
import com.szh.context.compaction.Summarizer;
import com.szh.context.dto.MessageItem;
import com.szh.context.dto.SystemMessageItem;
import com.szh.context.dto.UserMessageItem;
import com.szh.event.ContextCompactedEvent;
import com.szh.event.Event;
import com.szh.event.EventEnum;
import com.szh.event.UserMessageEvent;
import com.szh.store.EventJsonCodec;
import com.szh.store.FileEventStore;
import com.szh.store.MemoryEventStore;
import com.szh.trace.replay.TraceReplay;
import com.szh.utils.ConfigUtil;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 上下文压缩检查点（{@link ContextCompactedEvent}）冒烟验证（无测试框架，按项目惯例 main 直跑，
 * 注入 Fake 摘要器与进程内/临时文件事件存储，不依赖真实模型 API）：
 * <ol>
 *   <li>LlmCompactor：历史足够长时产出 {@code summarized} 结果（摘要正文 + keepRecentCount），过短时回退不产摘要；</li>
 *   <li>断点恢复：落检查点事件后 {@code resume()} 直接重建为 {@code [system, 摘要, 尾部若干条]}，不含被摘要掉的早期消息；</li>
 *   <li>事件编解码：ContextCompactedEvent 经 EventJsonCodec 往返关键字段不失真；</li>
 *   <li>多次压缩：两条检查点按事件顺序增量折叠，最终仅剩最新摘要 + 尾部；</li>
 *   <li>回放：TraceReplay 文本时间线渲染含 {@code [COMPACT]} 行且不抛异常，HTML 可导出。</li>
 * </ol>
 *
 * @author demussong
 * @date 2026/10/14
 */
@Slf4j
public class CompactionCheckpointSmokeTest {

    private static final AtomicInteger PASSED = new AtomicInteger();
    private static final AtomicInteger FAILED = new AtomicInteger();

    private static final int KEEP_RECENT = ConfigUtil.getInt("compaction.keep.recent", 6);

    public static void main(String[] args) throws Exception {
        // 冒烟默认用进程内事件存储，关掉长期记忆/沙箱/MCP，避免阻塞 stdin 或触达真实模型 API
        System.setProperty("store.engine", "MEMORY");
        System.setProperty("memory.enabled", "false");
        System.setProperty("memory.tools.enabled", "false");
        System.setProperty("shell.security.enabled", "false");
        System.setProperty("model.stream.enabled", "false");
        System.setProperty("mcp.enabled", "false");

        testLlmCompactorSummarized();
        testResumeReconstructsCompactedState();
        testCodecRoundTrip();
        testMultipleCompactions();
        testTraceReplayRenders();

        log.info("====== 压缩检查点冒烟结果: passed={}, failed={} ======", PASSED.get(), FAILED.get());
        if (FAILED.get() > 0) {
            System.exit(1);
        }
    }

    /**
     * LlmCompactor：足够长的历史产出 summarized 结果，摘要正文含 Fake 文本、keepRecentCount=KEEP_RECENT；
     * 历史过短（<= KEEP_RECENT+1）时不产摘要、回退 L0 语义。
     */
    private static void testLlmCompactorSummarized() {
        LlmCompactor compactor = new LlmCompactor(new FakeSummarizer("这是交接摘要"));

        List<MessageItem> ctx = new ArrayList<>();
        ctx.add(new SystemMessageItem("sys"));
        for (int i = 1; i <= 10; i++) {
            ctx.add(new UserMessageItem("m" + i));
        }
        CompactionResult r = compactor.compact(ctx);
        check("L1 触发产生摘要（isSummarized + changed）", r.isSummarized() && r.isChanged());
        check("保留尾部条数 = KEEP_RECENT", r.getKeepRecentCount() == KEEP_RECENT);
        check("摘要正文含 Fake 摘要文本", r.getSummary() != null && r.getSummary().contains("这是交接摘要"));
        check("压缩后结构 = system + 摘要 + 尾部", r.getMessages().size() == 2 + KEEP_RECENT);
        check("压缩后首条仍是原 system",
                r.getMessages().get(0) instanceof SystemMessageItem s && "sys".equals(s.getContent()));
        check("压缩后第二条是摘要 SystemMessageItem", r.getMessages().get(1) instanceof SystemMessageItem);

        // 历史过短：不产摘要
        List<MessageItem> small = new ArrayList<>();
        small.add(new SystemMessageItem("sys"));
        for (int i = 1; i <= 5; i++) {
            small.add(new UserMessageItem("m" + i));
        }
        CompactionResult bail = compactor.compact(small);
        check("历史过短不产生摘要（回退 L0 语义）", !bail.isSummarized());
    }

    /**
     * 断点恢复：运行期 replaceModelContext + 落检查点事件后，从同一 store resume()
     * 应直接重建为 [system, 摘要, 尾部 keepRecentCount 条]，且不含被摘要掉的早期消息。
     */
    private static void testResumeReconstructsCompactedState() {
        MemoryEventStore store = new MemoryEventStore();
        AgentState state = new AgentState(store);
        String session = "compact-resume-session";
        String runId = "run-1";
        String turnId = "turn_1";

        for (int i = 1; i <= 10; i++) {
            UserMessageItem item = new UserMessageItem("m" + i);
            state.applyEvent(new UserMessageEvent(session, runId, turnId, i, item, "m" + i));
        }
        check("运行期 modelContext = system + 10 条", state.getModelContext().size() == 11);

        LlmCompactor compactor = new LlmCompactor(new FakeSummarizer("交接摘要X"));
        CompactionResult r = compactor.compact(new ArrayList<>(state.getModelContext()));
        check("压缩产生摘要", r.isSummarized());

        // 模拟运行时：先替换内存上下文，再落检查点事件
        int before = state.getModelContext().size();
        state.replaceModelContext(r.getMessages());
        state.applyEvent(new ContextCompactedEvent(session, runId, turnId, 11,
                r.getSummary(), r.getKeepRecentCount(), before, r.getMessages().size()));

        // 从同一 store 全新重建
        AgentState resumed = new AgentState(store);
        resumed.resume(session);
        List<MessageItem> ctx = resumed.getModelContext();
        check("resume 重建为 system + 摘要 + 尾部", ctx.size() == 2 + r.getKeepRecentCount());
        check("resume 首条为 system", ctx.get(0) instanceof SystemMessageItem);
        check("resume 第二条为摘要且含 Fake 文本",
                ctx.get(1) instanceof SystemMessageItem s && s.getContent().contains("交接摘要X"));
        check("resume 不含被摘要掉的早期消息 m1",
                ctx.stream().noneMatch(m -> m instanceof UserMessageItem u && "m1".equals(u.getContent())));
        check("resume 保留尾部消息 m10",
                ctx.stream().anyMatch(m -> m instanceof UserMessageItem u && "m10".equals(u.getContent())));
        check("检查点事件已落库",
                store.getEvents(session).stream().anyMatch(e -> e.getType() == EventEnum.CONTEXT_COMPACTED));
    }

    /**
     * 事件编解码往返：ContextCompactedEvent 经 serialize -> deserialize 后关键字段不失真。
     */
    private static void testCodecRoundTrip() {
        ContextCompactedEvent ev = new ContextCompactedEvent("s", "r", "t", 3, "[历史摘要]\nabc", 6, 20, 8);
        Event back = EventJsonCodec.deserialize(EventJsonCodec.serialize(ev));
        check("ContextCompactedEvent 往返类型正确", back instanceof ContextCompactedEvent);
        check("往返保留 summary", back instanceof ContextCompactedEvent c && "[历史摘要]\nabc".equals(c.getSummary()));
        check("往返保留 keepRecentCount", back instanceof ContextCompactedEvent c && c.getKeepRecentCount() == 6);
        check("往返保留 before/after 消息数",
                back instanceof ContextCompactedEvent c && c.getBeforeMessages() == 20 && c.getAfterMessages() == 8);
        check("往返保留 round/turnId", back.getRound() == 3 && "t".equals(back.getTurnId()));
    }

    /**
     * 多次压缩：两条检查点按事件顺序增量折叠，最终仅剩最新摘要 + 尾部；旧摘要被覆盖不再出现。
     */
    private static void testMultipleCompactions() {
        MemoryEventStore store = new MemoryEventStore();
        AgentState state = new AgentState(store);
        String session = "compact-multi-session";
        String runId = "run-1";
        String turnId = "turn_1";

        for (int i = 1; i <= 10; i++) {
            state.applyEvent(new UserMessageEvent(session, runId, turnId, i, new UserMessageItem("m" + i), "m" + i));
        }
        // 检查点1：keep=2 → 折叠为 [system, 摘要1, m9, m10]
        state.applyEvent(new ContextCompactedEvent(session, runId, turnId, 11, "摘要1", 2, 10, 4));
        // 折叠后再追加两条消息
        state.applyEvent(new UserMessageEvent(session, runId, turnId, 12, new UserMessageItem("m11"), "m11"));
        state.applyEvent(new UserMessageEvent(session, runId, turnId, 13, new UserMessageItem("m12"), "m12"));
        // 检查点2：keep=1 → 在 [system, 摘要1, m9, m10, m11, m12] 上折叠为 [system, 摘要2, m12]
        state.applyEvent(new ContextCompactedEvent(session, runId, turnId, 14, "摘要2", 1, 6, 3));

        AgentState resumed = new AgentState(store);
        resumed.resume(session);
        List<MessageItem> ctx = resumed.getModelContext();
        check("多次压缩后仅剩 system + 最新摘要 + 尾部", ctx.size() == 3);
        check("最新摘要生效",
                ctx.get(1) instanceof SystemMessageItem s && "摘要2".equals(s.getContent()));
        check("尾部为最后一条 m12",
                ctx.get(2) instanceof UserMessageItem u && "m12".equals(u.getContent()));
        check("旧摘要1被覆盖不再出现",
                ctx.stream().noneMatch(m -> m instanceof SystemMessageItem s && "摘要1".equals(s.getContent())));
    }

    /**
     * 回放：用临时目录的 FILE 引擎落一条含检查点的 session，TraceReplay 文本时间线渲染出 [COMPACT] 行、
     * HTML 可导出且不抛异常（走真实的 codec 反序列化 + describe/appendHtmlEvent 分派路径）。
     */
    private static void testTraceReplayRenders() throws Exception {
        Path tmp = Files.createTempDirectory("compact-trace-");
        System.setProperty("store.engine", "FILE");
        System.setProperty("store.file.dir", tmp.toString());
        System.setProperty("trace.html.dir", tmp.toString());
        try {
            String session = "compact-trace-session";
            AgentState state = new AgentState(new FileEventStore());
            state.applyEvent(new UserMessageEvent(session, "run-1", "turn_1", 1, new UserMessageItem("m1"), "m1"));
            state.applyEvent(new ContextCompactedEvent(session, "run-1", "turn_1", 2, "[历史摘要]\n摘要正文", 1, 5, 3));

            String text = TraceReplay.render(session);
            check("文本时间线渲染出 [COMPACT] 行", text != null && text.contains("[COMPACT]"));
            check("[COMPACT] 行含压缩前后条数", text.contains("5→3"));

            Path html = TraceReplay.exportHtml(session);
            check("HTML 导出成功", html != null && Files.exists(html));
            check("HTML 含 COMPACT 分类", html != null && Files.readString(html).contains("COMPACT"));
        } finally {
            // 还原为进程内存储，避免影响其它用例
            System.setProperty("store.engine", "MEMORY");
            System.clearProperty("store.file.dir");
            System.clearProperty("trace.html.dir");
        }
    }

    /**
     * 假摘要器：返回预置文本，脱离真实模型 API 验证 L1 压缩链路
     */
    private static class FakeSummarizer implements Summarizer {
        private final String canned;

        FakeSummarizer(String canned) {
            this.canned = canned;
        }

        @Override
        public String summarize(List<MessageItem> messages) {
            return canned;
        }
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            PASSED.incrementAndGet();
            log.info("[PASS] {}", name);
        } else {
            FAILED.incrementAndGet();
            log.error("[FAIL] {}", name);
        }
    }
}
