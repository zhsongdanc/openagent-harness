package com.szh.test;

import com.szh.agent.AgentState;
import com.szh.context.dto.UserMessageItem;
import com.szh.event.UserMessageEvent;
import com.szh.store.MemoryEventStore;
import com.szh.tool.ToolContext;
import com.szh.tool.store.FileSnapshotStore;
import com.szh.tool.tools.file.EditFileTool;
import com.szh.tool.tools.file.WriteFileTool;
import com.szh.utils.CommonUtils;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 回溯（路径①：文件 before-image 快照 + 上下文/事件回退）冒烟测试。
 * <p>
 * main 直跑，不依赖真实模型 API / 外部构建：用临时工作区 + 进程内事件存储，覆盖
 * 轮次序号解析、写前快照捕获、每轮每文件幂等、跨轮回溯（还原修改 / 删除新建 / 保留更早检查点）、
 * 上下文按轮次截止重建、事件日志截断。对齐 {@code com.szh.tool.store.FileSnapshotStore} 语义。
 *
 * @author demussong
 * @date 2026/10/12
 */
@Slf4j
public class RewindSmokeTest {

    private static final AtomicInteger PASSED = new AtomicInteger();
    private static final AtomicInteger FAILED = new AtomicInteger();

    public static void main(String[] args) throws Exception {
        // 进程内事件存储 + 关闭长期记忆/沙箱交互，只验证回溯机制本身
        System.setProperty("store.engine", "MEMORY");
        System.setProperty("memory.enabled", "false");
        System.setProperty("memory.tools.enabled", "false");
        System.setProperty("shell.security.enabled", "false");
        System.setProperty("model.stream.enabled", "false");
        System.setProperty("rewind.enabled", "true");

        Path workspace = Files.createTempDirectory("rewind-smoke");
        System.setProperty("project.workspace", workspace.toString());
        log.info("回溯冒烟工作区: {}", workspace);

        testParseTurnSeq();
        testSnapshotCreateAndRestore(workspace);
        testRewindAcrossTurns(workspace);
        testContextRewindAndTruncate();

        log.info("====== Rewind 冒烟结果: passed={}, failed={} ======", PASSED.get(), FAILED.get());
        if (FAILED.get() > 0) {
            System.exit(1);
        }
    }

    /**
     * 轮次序号解析：末尾连续数字，异常输入兜底 0
     */
    private static void testParseTurnSeq() {
        check("parseTurnSeq turn_7 -> 7", CommonUtils.parseTurnSeq("turn_7") == 7);
        check("parseTurnSeq turn_12 -> 12（数值序非字典序）", CommonUtils.parseTurnSeq("turn_12") == 12);
        check("parseTurnSeq null -> 0", CommonUtils.parseTurnSeq(null) == 0);
        check("parseTurnSeq 无数字 -> 0", CommonUtils.parseTurnSeq("abc") == 0);
    }

    /**
     * 新建文件的快照与回溯：turn 内多次改动幂等（只记首次改动前镜像），回溯到该轮之前即删除新建文件
     */
    private static void testSnapshotCreateAndRestore(Path workspace) throws Exception {
        String session = "s-create";
        WriteFileTool write = new WriteFileTool();
        EditFileTool edit = new EditFileTool();

        write.execute(ctx(workspace, session, "turn_1", "{\"path\":\"a.txt\",\"content\":\"v1\"}"));
        check("write_file 创建 a.txt=v1", Files.readString(workspace.resolve("a.txt")).equals("v1"));
        check("捕获到 1 个检查点", FileSnapshotStore.listCheckpoints(ws(workspace), session).size() == 1);

        // 同轮再编辑同文件：应幂等，不新增条目，仍保留「首次改动前=不存在」的镜像
        edit.execute(ctx(workspace, session, "turn_1", "{\"path\":\"a.txt\",\"old_text\":\"v1\",\"new_text\":\"v1-edit\"}"));
        check("edit_file 改成 v1-edit", Files.readString(workspace.resolve("a.txt")).equals("v1-edit"));
        check("同轮同文件幂等，仍 1 个条目",
                FileSnapshotStore.listCheckpoints(ws(workspace), session).get(0).getEntries().size() == 1);

        // 回溯到 turn 1 之前：a.txt 是本轮新建的 -> 删除
        FileSnapshotStore.RestoreResult rr = FileSnapshotStore.restoreToTurn(ws(workspace), session, 1);
        check("回溯删除本轮新建的 a.txt", !Files.exists(workspace.resolve("a.txt")));
        check("deleted 计数=1", rr.getDeleted() == 1);
        check("回溯后检查点已清理", FileSnapshotStore.listCheckpoints(ws(workspace), session).isEmpty());
    }

    /**
     * 跨轮回溯：对每个文件取「turnSeq>=目标中最早的快照」还原，撤销新建，保留更早轮次的检查点
     */
    private static void testRewindAcrossTurns(Path workspace) throws Exception {
        String session = "s-across";
        WriteFileTool write = new WriteFileTool();
        EditFileTool edit = new EditFileTool();

        // b.txt 在任何轮次之前就已存在（模拟用户既有文件）
        Files.writeString(workspace.resolve("b.txt"), "original");

        // turn 1：改 b.txt
        edit.execute(ctx(workspace, session, "turn_1", "{\"path\":\"b.txt\",\"old_text\":\"original\",\"new_text\":\"turn1\"}"));
        check("turn1 b.txt=turn1", Files.readString(workspace.resolve("b.txt")).equals("turn1"));

        // turn 2：再改 b.txt + 新建 c.txt
        edit.execute(ctx(workspace, session, "turn_2", "{\"path\":\"b.txt\",\"old_text\":\"turn1\",\"new_text\":\"turn2\"}"));
        write.execute(ctx(workspace, session, "turn_2", "{\"path\":\"c.txt\",\"content\":\"c-turn2\"}"));
        check("turn2 b.txt=turn2", Files.readString(workspace.resolve("b.txt")).equals("turn2"));
        check("turn2 新建 c.txt", Files.exists(workspace.resolve("c.txt")));
        check("共 2 个检查点", FileSnapshotStore.listCheckpoints(ws(workspace), session).size() == 2);

        // 回溯到 turn 2 之前：b 回到 turn1，c 被删，turn1 检查点保留
        FileSnapshotStore.restoreToTurn(ws(workspace), session, 2);
        check("回溯到 turn2 前 b.txt=turn1", Files.readString(workspace.resolve("b.txt")).equals("turn1"));
        check("回溯到 turn2 前 c.txt 被删", !Files.exists(workspace.resolve("c.txt")));
        check("turn1 检查点保留", FileSnapshotStore.listCheckpoints(ws(workspace), session).size() == 1);

        // 再回溯到 turn 1 之前：b 回到 original，检查点全清
        FileSnapshotStore.restoreToTurn(ws(workspace), session, 1);
        check("回溯到 turn1 前 b.txt=original", Files.readString(workspace.resolve("b.txt")).equals("original"));
        check("检查点全部清空", FileSnapshotStore.listCheckpoints(ws(workspace), session).isEmpty());
    }

    /**
     * 上下文按轮次截止重建 + 事件日志截断（二者独立验证）
     */
    private static void testContextRewindAndTruncate() {
        MemoryEventStore store = new MemoryEventStore();
        AgentState state = new AgentState(store);
        String session = "s-ctx";
        state.applyEvent(new UserMessageEvent(session, "r1", "turn_1", 0, new UserMessageItem("问题1"), "问题1"));
        state.applyEvent(new UserMessageEvent(session, "r2", "turn_2", 0, new UserMessageItem("问题2"), "问题2"));
        state.applyEvent(new UserMessageEvent(session, "r3", "turn_3", 0, new UserMessageItem("问题3"), "问题3"));
        check("三条用户事件落库", store.getEvents(session).size() == 3);

        // 未截断也能按截止轮次重建上下文（system + turn1 + turn2）
        state.resume(session, 3);
        check("resume 截止重建到 turn3 前（system+2 条）", state.getModelContext().size() == 3);
        check("resume 回拨 turnId=2", state.getTurnId() == 2);

        // 事件截断：删除 turnSeq>=3 的事件
        store.truncateFromTurn(session, 3);
        check("truncateFromTurn 删除 turn>=3", store.getEvents(session).size() == 2);
        check("截断后仅剩 turn_1/turn_2",
                store.getEvents(session).stream().allMatch(e -> CommonUtils.parseTurnSeq(e.getTurnId()) < 3));
    }

    private static String ws(Path workspace) {
        return workspace.toString();
    }

    private static ToolContext ctx(Path workspace, String sessionId, String turnId, String args) {
        ToolContext c = new ToolContext(sessionId, "run-x", workspace.toString(), args);
        c.setTurnId(turnId);
        c.setRound(1);
        return c;
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
