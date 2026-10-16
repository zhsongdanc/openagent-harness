package com.szh.test;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.szh.agent.AgentState;
import com.szh.agent.LoopGuard;
import com.szh.agent.ParallelToolExecutor;
import com.szh.event.CallToolFinishedEvent;
import com.szh.event.Event;
import com.szh.model.dto.ToolCall;
import com.szh.store.MemoryEventStore;
import com.szh.tool.ToolRegistry;
import com.szh.tool.store.ToolResultStore;
import com.szh.utils.JsonUtil;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author demussong
 * @describe 同文件并发写冲突回归冒烟（main 直跑，不依赖模型 API）。
 * <p>
 * 复现的真实故障：Agent 一轮内对同一文件并行发起多个 edit_file（如同时改超时配置和补 import），
 * ParallelToolExecutor 并发执行时两个 worker 各自读到同一份旧内容，后写者基于旧快照整体覆盖
 * 先写者——两边都回执"成功"，但其中一处改动被静默丢失，直到下一轮编译才暴露。
 * 修复：FileToolSupport 按规范化真实路径加写锁，同路径串行、不同路径保持并行。
 * <p>
 * 覆盖：
 * <ol>
 *   <li>同轮同文件双编辑必须叠加保留（多轮重复，去掉路径锁后大概率复现丢写）；</li>
 *   <li>同文件 8 路并发编辑压力：全部改动在最终文件恰好出现一次；</li>
 *   <li>不同路径并发编辑不被串行误伤：全部成功且内容互不污染；</li>
 *   <li>同轮 write_file + edit_file 混发同一文件：最终必须是二者之一的一致完整态，不出现半新半旧。</li>
 * </ol>
 * @date 2026-10-16
 */
@Slf4j
public class FileWriteConflictSmokeTest {

    private static final AtomicInteger PASSED = new AtomicInteger();
    private static final AtomicInteger FAILED = new AtomicInteger();

    /** 场景①重复轮数：并发调度不可控，多跑几轮提高「锁被移除」时的复现概率 */
    private static final int REPEAT_ROUNDS = 24;

    private static Path workspace;

    public static void main(String[] args) throws Exception {
        System.setProperty("store.engine", "MEMORY");
        System.setProperty("memory.enabled", "false");
        System.setProperty("memory.tools.enabled", "false");
        System.setProperty("shell.security.enabled", "false");
        System.setProperty("mcp.enabled", "false");
        // 压力场景 8 个调用全部真正进线程池，最大化竞争窗口
        System.setProperty("agent.parallel.maxThreads", "8");

        workspace = Files.createTempDirectory("file-write-conflict");
        log.info("冒烟工作区: {}", workspace);

        testSameFileDualEditMerged();
        testSameFileParallelStress();
        testDifferentFilesParallel();
        testMixedWriteAndEditSameFile();

        log.info("====== 同文件并发写冲突冒烟结果: passed={}, failed={} ======", PASSED.get(), FAILED.get());
        if (FAILED.get() > 0) {
            System.exit(1);
        }
    }

    /**
     * ①事故场景最小复现：同一轮对同一文件并行「改超时常量」+「补 import」，
     * 两处改动都必须出现在最终文件（而不是各自报告成功后互相覆盖）
     */
    private static void testSameFileDualEditMerged() throws Exception {
        String base = """
                package demo;

                public class ShellCommandTool {
                    private static final long TIMEOUT_SECONDS = 30;
                }
                """;
        int lost = 0;
        int errored = 0;
        for (int i = 0; i < REPEAT_ROUNDS; i++) {
            String session = "dual-" + i;
            Files.writeString(workspace.resolve(session + ".java"), base, StandardCharsets.UTF_8);
            List<ToolCall> calls = List.of(
                    editCall(session + ".java", "TIMEOUT_SECONDS = 30", "TIMEOUT_SECONDS = 120"),
                    editCall(session + ".java", "package demo;",
                            "package demo;\n\nimport com.szh.utils.ConfigUtil;"));
            List<CallToolFinishedEvent> outs = runBatch(session, calls);

            String finalContent = Files.readString(workspace.resolve(session + ".java"), StandardCharsets.UTF_8);
            boolean hasTimeout = finalContent.contains("TIMEOUT_SECONDS = 120");
            boolean hasImport = finalContent.contains("import com.szh.utils.ConfigUtil;");
            if (!hasTimeout || !hasImport) {
                lost++;
            }
            for (CallToolFinishedEvent e : outs) {
                if (!e.getOutput().startsWith("已编辑")) {
                    errored++;
                }
            }
        }
        check(REPEAT_ROUNDS + " 轮同文件双编辑全部叠加保留（无丢写）", lost == 0);
        check("双编辑回执均为成功（未被并发读到旧内容导致定位失败）", errored == 0);
    }

    /**
     * ②压力：8 路并发编辑同一文件，各自在 END 哨兵前插入唯一标记。
     * 无锁时任一交错都会丢标记或重复插入；有锁则全部按序叠加
     */
    private static void testSameFileParallelStress() throws Exception {
        Path file = workspace.resolve("stress.java");
        Files.writeString(file, "start\nEND\n", StandardCharsets.UTF_8);

        int n = 8;
        List<ToolCall> calls = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            calls.add(editCall("stress.java", "END", "marker-" + i + "\nEND"));
        }
        runBatch("stress-session", calls);

        String content = Files.readString(file, StandardCharsets.UTF_8);
        boolean allMarkers = true;
        for (int i = 0; i < n; i++) {
            if (countOccurrences(content, "marker-" + i) != 1) {
                allMarkers = false;
            }
        }
        check("8 路并发同文件编辑：每个标记恰好出现一次", allMarkers);
        check("END 哨兵未被复制损坏（无基于旧内容的重复插入）", countOccurrences(content, "END") == 1);
    }

    /**
     * ③不同路径的并发编辑仍应全部成功且互不污染（验证串行化只按同路径生效，没把并行打死）
     */
    private static void testDifferentFilesParallel() throws Exception {
        int n = 6;
        List<ToolCall> calls = new ArrayList<>();
        List<Path> files = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Path f = workspace.resolve("par-" + i + ".txt");
            Files.writeString(f, "head-" + i + "\ntail-" + i + "\n", StandardCharsets.UTF_8);
            files.add(f);
            calls.add(editCall("par-" + i + ".txt", "head-" + i, "HEAD-" + i));
        }
        List<CallToolFinishedEvent> outs = runBatch("par-session", calls);

        boolean allOk = outs.size() == n;
        for (int i = 0; i < n && allOk; i++) {
            allOk = outs.get(i).getOutput().startsWith("已编辑");
        }
        check("不同路径并发编辑全部成功（无死锁/误串行）", allOk);
        boolean isolated = true;
        for (int i = 0; i < n; i++) {
            String content = Files.readString(files.get(i), StandardCharsets.UTF_8);
            isolated &= content.contains("HEAD-" + i) && content.contains("tail-" + i);
        }
        check("各文件只保留自己的改动，互不污染", isolated);
    }

    /**
     * ④同轮 write_file（全量覆盖）+ edit_file 混发同一文件：无论谁先执行，
     * 最终只能是「edit 后又被 write 整体覆盖」或「write 后 edit 叠加」两种一致完整态，
     * 不能出现基于旧 base 的覆盖残留（旧内容 OLD 复活）
     */
    private static void testMixedWriteAndEditSameFile() throws Exception {
        Path file = workspace.resolve("mix.java");
        String base = "package demo;\nOLD-BASELINE\n";
        String fresh = "package demo;\nFRESH-CONTENT\n";
        Files.writeString(file, base, StandardCharsets.UTF_8);

        ObjectNode writeArgs = JsonUtil.getMapper().createObjectNode();
        writeArgs.put("path", "mix.java");
        writeArgs.put("content", fresh);
        List<ToolCall> calls = List.of(
                new ToolCall("mix-write", "write_file", writeArgs.toString()),
                editCall("mix.java", "package demo;", "package demo;\n// anchor"));
        runBatch("mix-session", calls);

        String content = Files.readString(file, StandardCharsets.UTF_8);
        boolean consistent = content.equals(fresh)
                || content.equals("package demo;\n// anchor\nFRESH-CONTENT\n");
        check("write+edit 混发后最终文件是二选一的一致完整态", consistent);
        check("旧基线内容未被并发覆盖复活", !content.contains("OLD-BASELINE"));
    }

    // ==================== 工具方法 ====================

    private static ToolCall editCall(String path, String oldText, String newText) {
        ObjectNode node = JsonUtil.getMapper().createObjectNode();
        node.put("path", path);
        node.put("old_text", oldText);
        node.put("new_text", newText);
        return new ToolCall("edit-" + Math.abs((path + oldText).hashCode()), "edit_file", node.toString());
    }

    /** 走生产同款路径：ParallelToolExecutor.executeBatch 并发执行一轮调用 */
    private static List<CallToolFinishedEvent> runBatch(String session, List<ToolCall> calls) {
        AgentState agentState = new AgentState(new MemoryEventStore());
        ToolRegistry registry = new ToolRegistry();
        ToolResultStore store = new ToolResultStore(workspace.toString(), session);
        ParallelToolExecutor.executeBatch(calls, new ParallelToolExecutor.ExecutionEnv(
                agentState, registry, session, "run-" + session, "turn_1", 1,
                workspace.toString(), store, new LoopGuard()));

        List<CallToolFinishedEvent> outs = new ArrayList<>();
        for (Event event : agentState.getEventStore().getEvents(session)) {
            if (event instanceof CallToolFinishedEvent finished) {
                outs.add(finished);
            }
        }
        return outs;
    }

    private static int countOccurrences(String content, String target) {
        int count = 0;
        int idx = content.indexOf(target);
        while (idx >= 0) {
            count++;
            idx = content.indexOf(target, idx + target.length());
        }
        return count;
    }

    private static void check(String label, boolean ok) {
        if (ok) {
            PASSED.incrementAndGet();
            log.info("[PASS] {}", label);
        } else {
            FAILED.incrementAndGet();
            log.error("[FAIL] {}", label);
        }
    }
}
