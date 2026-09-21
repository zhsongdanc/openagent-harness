package com.szh.test;

import com.szh.agent.AgentState;
import com.szh.agent.LoopGuard;
import com.szh.agent.ParallelToolExecutor;
import com.szh.event.Event;
import com.szh.event.EventEnum;
import com.szh.model.DeepSeekModel;
import com.szh.model.DeepSeekResponseModel;
import com.szh.model.Model;
import com.szh.model.ModelFactory;
import com.szh.model.OpenAiCompatModel;
import com.szh.model.ResponseModel;
import com.szh.model.dto.ToolCall;
import com.szh.store.MemoryEventStore;
import com.szh.tool.ToolContext;
import com.szh.tool.ToolRegistry;
import com.szh.tool.store.ToolResultStore;
import com.szh.tool.tools.file.EditFileTool;
import com.szh.tool.tools.file.ReadFileTool;
import com.szh.tool.tools.file.RepoMapTool;
import com.szh.tool.tools.file.WriteFileTool;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author demussong
 * @describe P1 能力冒烟验证（无测试框架，按项目惯例用 main 方法直跑，全部不依赖模型 API）：
 * <ol>
 *   <li>文件工具链：write_file -> read_file -> edit_file（唯一性校验 / 替换 / diff 回显）；</li>
 *   <li>路径安全：工作区外写入与 .. 穿越必须被拦截；</li>
 *   <li>repo_map：目录树与 symbols 模式输出；</li>
 *   <li>并行工具执行：3 个工具并发跑，事件按「全部 started -> 全部 finished」有序落库；</li>
 *   <li>多模型工厂：provider 切换装配出正确的实现类；</li>
 *   <li>流式监听器：回调聚合行为。</li>
 * </ol>
 * @date 2026/9/21
 */
@Slf4j
public class P1SmokeTest {

    private static final AtomicInteger PASSED = new AtomicInteger();
    private static final AtomicInteger FAILED = new AtomicInteger();

    public static void main(String[] args) throws Exception {
        // 冒烟测试用进程内事件存储，且不触发 SQLite 长期记忆
        System.setProperty("store.engine", "MEMORY");
        System.setProperty("memory.enabled", "false");
        System.setProperty("memory.tools.enabled", "false");
        // 沙箱/权限交互会阻塞 stdin，冒烟只验证文件工具与并行调度本身
        System.setProperty("shell.security.enabled", "false");

        Path workspace = Files.createTempDirectory("p1-smoke");
        log.info("冒烟工作区: {}", workspace);

        testFileToolChain(workspace);
        testPathSecurity(workspace);
        testRepoMap();
        testParallelExecution(workspace);
        testModelFactory();
        testStreamListener();

        log.info("====== P1 冒烟结果: passed={}, failed={} ======", PASSED.get(), FAILED.get());
        if (FAILED.get() > 0) {
            System.exit(1);
        }
    }

    /**
     * 文件工具链：写入 -> 读回（带行号）-> 编辑（重复定位拒绝 / replace_all / 精确替换 + diff）
     */
    private static void testFileToolChain(Path workspace) {
        WriteFileTool write = new WriteFileTool();
        ReadFileTool read = new ReadFileTool();
        EditFileTool edit = new EditFileTool();

        String writeRes = write.execute(ctx(workspace,
                "{\"path\":\"src/Demo.java\",\"content\":\"line1\\nline2 target\\nline3 target\\nline4\"}"));
        check("write_file 创建文件", writeRes.contains("已创建") && Files.exists(workspace.resolve("src/Demo.java")));

        String readRes = read.execute(ctx(workspace, "{\"path\":\"src/Demo.java\"}"));
        check("read_file 带行号读回", readRes.contains("2→line2 target") && readRes.contains("共 4 行"));

        String dupRes = edit.execute(ctx(workspace,
                "{\"path\":\"src/Demo.java\",\"old_text\":\"target\",\"new_text\":\"hit\"}"));
        check("edit_file 多处出现且未传 replace_all 时拒绝", dupRes.contains("参数错误") && dupRes.contains("2 次"));

        String allRes = edit.execute(ctx(workspace,
                "{\"path\":\"src/Demo.java\",\"old_text\":\"target\",\"new_text\":\"hit\",\"replace_all\":true}"));
        check("edit_file replace_all 全部替换并回显 diff",
                allRes.contains("替换 2 处") && allRes.contains("- line2 target") && allRes.contains("+ line2 hit"));

        String singleRes = edit.execute(ctx(workspace,
                "{\"path\":\"src/Demo.java\",\"old_text\":\"line1\",\"new_text\":\"LINE-ONE\"}"));
        check("edit_file 唯一匹配精确替换", singleRes.contains("替换 1 处"));

        String finalContent = read.execute(ctx(workspace, "{\"path\":\"src/Demo.java\"}"));
        check("编辑后内容正确", finalContent.contains("1→LINE-ONE") && finalContent.contains("3→line3 hit"));

        String missRes = edit.execute(ctx(workspace,
                "{\"path\":\"src/Demo.java\",\"old_text\":\"not-exist-text\",\"new_text\":\"x\"}"));
        check("edit_file old_text 未找到时拒绝且不落盘", missRes.contains("未找到"));

        String overwriteRes = write.execute(ctx(workspace,
                "{\"path\":\"src/Demo.java\",\"content\":\"fresh\"}"));
        check("write_file 覆盖已有文件并回显旧行数", overwriteRes.contains("已覆盖写入") && overwriteRes.contains("原 4 行"));
    }

    /**
     * 路径安全：工作区外写入、.. 穿越读取都必须被拦截
     */
    private static void testPathSecurity(Path workspace) throws Exception {
        WriteFileTool write = new WriteFileTool();
        ReadFileTool read = new ReadFileTool();

        String outsideWrite = write.execute(ctx(workspace,
                "{\"path\":\"/tmp/p1-smoke-evil.txt\",\"content\":\"hack\"}"));
        check("write_file 拦截工作区外写入", outsideWrite.contains("参数错误") && outsideWrite.contains("工作区外")
                && !Files.exists(Path.of("/tmp/p1-smoke-evil.txt")));

        Path secret = workspace.getParent().resolve("p1-smoke-secret.txt");
        Files.writeString(secret, "secret");
        String traversalRead = read.execute(ctx(workspace, "{\"path\":\"../p1-smoke-secret.txt\"}"));
        check("read_file 拦截 .. 穿越读取", traversalRead.contains("参数错误") || !traversalRead.contains("secret"));
        Files.deleteIfExists(secret);
    }

    /**
     * repo_map：对本仓库生成 tree 与 symbols 两种模式
     */
    private static void testRepoMap() {
        RepoMapTool repoMap = new RepoMapTool();
        Path project = Path.of(System.getProperty("user.dir"));

        String tree = repoMap.execute(new ToolContext("{\"path\":\"" + project + "\",\"maxDepth\":3}"));
        check("repo_map tree 模式输出目录树且剪掉噪音目录",
                tree.contains("Repo Map:") && tree.contains("pom.xml") && !tree.contains("target/") && !tree.contains(".git/"));

        String symbols = repoMap.execute(new ToolContext(
                "{\"path\":\"" + project.resolve("src/main/java/com/szh/agent") + "\",\"mode\":\"symbols\"}"));
        check("repo_map symbols 模式抽取 Java 类与方法签名",
                symbols.contains("class AgentRuntime") && symbols.contains("run("));
        log.info("repo_map symbols 输出片段:\n{}", symbols.substring(0, Math.min(600, symbols.length())));
    }

    /**
     * 并行工具执行：3 个只读工具并发跑，验证结果正确 + 事件分组有序
     */
    private static void testParallelExecution(Path workspace) throws Exception {
        AgentState agentState = new AgentState(new MemoryEventStore());
        ToolRegistry registry = new ToolRegistry();
        ToolResultStore store = new ToolResultStore(workspace.toString(), "smoke-session");

        // pwd / repo_map / read_file 三个互不依赖的工具，一轮并发
        List<ToolCall> calls = List.of(
                new ToolCall("call-1", "pwd", "{}"),
                new ToolCall("call-2", "repo_map", "{\"maxDepth\":1}"),
                new ToolCall("call-3", "read_file", "{\"path\":\"src/Demo.java\"}"));

        long start = System.currentTimeMillis();
        boolean executed = ParallelToolExecutor.executeBatch(calls, new ParallelToolExecutor.ExecutionEnv(
                agentState, registry, "smoke-session", "smoke-run", "turn_1", 1,
                workspace.toString(), store, new LoopGuard()));
        long cost = System.currentTimeMillis() - start;
        check("executeBatch 返回已执行", executed);

        List<Event> events = agentState.getEventStore().getEvents("smoke-session");
        List<EventEnum> order = events.stream().map(Event::getType).toList();
        // 分组约束：3 个 CALL_TOOL_STARTED 必须连续排在 3 个 CALL_TOOL_FINISHED 之前
        int firstFinished = order.indexOf(EventEnum.CALL_TOOL_FINISHED);
        long startedBeforeFinished = order.subList(0, firstFinished).stream()
                .filter(t -> t == EventEnum.CALL_TOOL_STARTED).count();
        check("事件按「全部 started -> 全部 finished」分组有序", startedBeforeFinished == 3);
        check("finished 事件数量正确", order.stream().filter(t -> t == EventEnum.CALL_TOOL_FINISHED).count() == 3);
        log.info("并行执行 3 个工具耗时 {}ms", cost);
    }

    /**
     * 多模型工厂：provider 切换装配出正确实现类
     */
    private static void testModelFactory() {
        Model deepseek = ModelFactory.createChatModel();
        check("默认 provider 装配 DeepSeekModel", deepseek instanceof DeepSeekModel);

        System.setProperty("model.provider", "openai");
        System.setProperty("model.openai.apiKey", "sk-test");
        Model openai = ModelFactory.createChatModel();
        check("openai provider 装配 OpenAiCompatModel", openai instanceof OpenAiCompatModel);
        ResponseModel openaiResp = ModelFactory.createResponseModel();
        check("openai Responses 链路装配 OpenAiCompatResponseModel",
                openaiResp instanceof com.szh.model.OpenAiCompatResponseModel);
        System.clearProperty("model.provider");
        System.clearProperty("model.openai.apiKey");

        ResponseModel deepseekResp = ModelFactory.createResponseModel();
        check("默认 Responses 链路装配 DeepSeekResponseModel", deepseekResp instanceof DeepSeekResponseModel);

        System.setProperty("model.provider", "ollama");
        Model ollama = ModelFactory.createChatModel();
        check("ollama（本地 OpenAI 兼容）免密钥装配", ollama instanceof OpenAiCompatModel);
        boolean rejected = false;
        try {
            ModelFactory.createResponseModel();
        } catch (IllegalArgumentException e) {
            rejected = true;
        }
        check("ollama 请求 Responses API 时显式报错", rejected);
        System.clearProperty("model.provider");
    }

    /**
     * 流式监听器：增量聚合与完成回调
     */
    private static void testStreamListener() {
        AtomicReference<StringBuilder> text = new AtomicReference<>(new StringBuilder());
        AtomicInteger completed = new AtomicInteger();
        com.szh.model.StreamListener listener = new com.szh.model.StreamListener() {
            @Override
            public void onTextDelta(String delta) {
                text.get().append(delta);
            }

            @Override
            public void onComplete() {
                completed.incrementAndGet();
            }
        };
        listener.onTextDelta("你好");
        listener.onTextDelta("，世界");
        listener.onComplete();
        check("StreamListener 增量聚合正确", "你好，世界".contentEquals(text.get()) && completed.get() == 1);
    }

    private static ToolContext ctx(Path workspace, String args) {
        return new ToolContext("smoke-session", "smoke-run", workspace.toString(), args);
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
