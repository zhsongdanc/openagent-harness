package com.szh.memory;

import com.szh.context.compaction.Summarizer;
import com.szh.context.dto.MessageItem;
import com.szh.context.dto.SystemMessageItem;
import com.szh.utils.ConfigUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 长期记忆门面（L4）：对运行时暴露一个极简、稳定、绝不抛异常的入口，
 * 内部封装 SQLite FTS5 存储、检索召回与反思抽取。
 * <p>
 * 采用懒加载单例，从配置读取开关与参数；<b>任何初始化或运行失败都优雅降级为“记忆功能关闭”</b>，
 * 保证即便本机未就绪 SQLite，也不会影响主 Agent 循环。
 * <p>
 * 三个能力：
 * <ul>
 *   <li>{@link #remember(Memory)}：显式写入（write-through）：不排队不入队，但同样交给
 *       {@link MemoryConsolidator} 做语义整理（ADD/UPDATE/MERGE/SUPERSEDE/SKIP），取代旧「相似即覆盖」；</li>
 *   <li>{@link #injectRecall(List, String, String)} / {@link #recall(String, MemoryScope, int, String)}：按 query 召回
 *       （带项目与会话可见性）；</li>
 *   <li>{@link #forget(String)} / {@link #forgetByQuery(String, MemoryScope, int, String)}：遗忘（供工具主动调用）；</li>
 *   <li>{@link #maybeReflectOnRunEnd}：run 结束后按策略异步蒸馏，含「压缩丢弃内容抢救」独立通道；
 *       自动通道产出的是 {@link MemoryCandidate} 候选（SQLite 落库、指纹幂等），经整理器决定去留；</li>
 *   <li>{@link #reflectAndStore(String, List, Summarizer)}：同步强制蒸馏入口（测试/特殊场景）。</li>
 * </ul>
 *
 * @author demussong
 * @date 2026/9/21
 */
@Slf4j
public class LongTermMemory {

    private static volatile LongTermMemory instance;

    private final boolean enabled;
    private final boolean retrievalEnabled;
    private final boolean reflectionEnabled;
    private final int topK;
    /** 写入去重开关：命中近似既有记忆时整理而非新增（传给整理器，关闭则候选直接 ADD） */
    private final boolean dedupEnabled;
    /** 相似判定 mid 档下界（原「去重阈值」：达它才认为相关） */
    private final double dedupThreshold;
    /** 近重复档下界：达它不再细判关系，直接按完全重复/同一事实修正处理 */
    private final double highThreshold;
    /** 去重时拉取的候选条数 */
    private final int dedupCandidates;
    /** 候选层总开关（kill switch）：关后不建候选表、不写日志，提取结果直接过整理器落库 */
    private final boolean candidateEnabled;
    /** 已处理候选的保留条数，超出后清理最旧 */
    private final int candidateKeepRecent;
    /** mid 区间是否允许 LLM 仲裁（仅自动异步通道生效） */
    private final boolean consolidationLlmEnabled;
    /** 记忆库容量上限，<=0 表示不限；超限按最旧淘汰 */
    private final int capacityMax;
    /** 存储初始化失败时为 null，所有操作自动变为 no-op */
    private final MemoryStore store;
    /** 候选层存储，null 表示候选层降级/关闭（不持久化候选、不写整理日志） */
    private final CandidateStore candidateStore;
    /** 记忆整理器：候选→正式库的六种动作决策核心，store 就绪时非 null */
    private final MemoryConsolidator consolidator;
    /** 反思触发策略：决定 run/会话结束时是否真的跑 LLM 蒸馏（取代旧的「每轮无条件反思」） */
    private final ReflectionPolicy reflectionPolicy;
    /** JVM 退出前等待在途异步反思任务排空的最长秒数 */
    private final int reflectionShutdownTimeoutSeconds;
    /** 单线程守护执行器：串行跑异步反思，避免与主对话争抢并发的 LLM 调用；懒创建 */
    private volatile ExecutorService reflectionExecutor;
    /** 项目标识缓存：仅当 project.workspace 原始值变化时重算真实路径（toRealPath 是磁盘操作） */
    private volatile String cachedWorkspaceRaw;
    private volatile String cachedProjectKey;

    private LongTermMemory() {
        this.enabled = ConfigUtil.getBoolean("memory.enabled", true);
        this.retrievalEnabled = ConfigUtil.getBoolean("memory.retrieval.enabled", true);
        this.reflectionEnabled = ConfigUtil.getBoolean("memory.reflection.enabled", true);
        this.topK = ConfigUtil.getInt("memory.retrieval.topK", 5);
        this.dedupEnabled = ConfigUtil.getBoolean("memory.dedup.enabled", true);
        this.dedupThreshold = ConfigUtil.getDouble("memory.dedup.threshold", 0.72);
        this.highThreshold = ConfigUtil.getDouble("memory.dedup.highThreshold", 0.92);
        this.dedupCandidates = ConfigUtil.getInt("memory.dedup.candidates", 8);
        this.candidateEnabled = ConfigUtil.getBoolean("memory.candidate.enabled", true);
        this.candidateKeepRecent = ConfigUtil.getInt("memory.candidate.keepRecent", 500);
        this.consolidationLlmEnabled = ConfigUtil.getBoolean("memory.consolidation.llm.enabled", true);
        this.capacityMax = ConfigUtil.getInt("memory.capacity.max", 1000);
        this.reflectionShutdownTimeoutSeconds = ConfigUtil.getInt("memory.reflection.shutdownTimeoutSeconds", 30);

        MemoryStore s = null;
        if (enabled) {
            try {
                s = new SqliteMemoryStore();
            } catch (Throwable t) {
                // 驱动缺失/建表失败等：降级关闭，不打断启动
                log.error("LongTermMemory: store init failed, long-term memory disabled. ({})", t.toString());
            }
        }
        this.store = s;
        // 候选层初始化失败只关闭候选层（无候选记录/日志），不影响正式库主链路（铁律 6）
        CandidateStore cs = null;
        if (s != null && candidateEnabled) {
            try {
                cs = new SqliteCandidateStore();
            } catch (Throwable t) {
                log.error("LongTermMemory: candidate store init failed, candidate layer disabled. ({})", t.toString());
            }
        }
        this.candidateStore = cs;
        this.consolidator = s == null ? null
                : new MemoryConsolidator(s, cs, dedupEnabled, dedupThreshold, highThreshold,
                        dedupCandidates, consolidationLlmEnabled);
        this.reflectionPolicy = new ReflectionPolicy();
        log.info("LongTermMemory: enabled={}, retrieval={}, reflection={}(trigger={}), dedup={}(th={}-{}), "
                        + "candidate={}, capacity={}, storeReady={}",
                enabled, retrievalEnabled, reflectionEnabled, reflectionPolicy.getTrigger(), dedupEnabled,
                dedupThreshold, highThreshold, cs != null, capacityMax > 0 ? capacityMax : "unlimited", store != null);
        // 退出前尽量把在途的异步反思落库，避免 --prompt 一次性执行或 REPL 退出时丢失最后一次蒸馏
        if (reflectionEnabled && store != null) {
            Runtime.getRuntime().addShutdownHook(new Thread(this::awaitPendingReflection, "ltm-reflection-shutdown"));
        }
    }

    public static LongTermMemory get() {
        if (instance == null) {
            synchronized (LongTermMemory.class) {
                if (instance == null) {
                    instance = new LongTermMemory();
                }
            }
        }
        return instance;
    }

    /**
     * 记忆功能是否真正可用（总开关开启且存储就绪）
     */
    public boolean isActive() {
        return enabled && store != null;
    }

    public MemoryStore getStore() {
        return store;
    }

    // ---------------- 项目归属 ----------------

    /**
     * 当前项目标识：{@code project.workspace} 规范化后的真实路径（macOS /tmp 等符号链接必须
     * toRealPath，否则同一目录会被判为两个项目）。每次调用实时解析而非构造期固化：单例跨 REPL
     * 重启/测试切仓复用同一 JVM，workspace 可能变；与 InstructionMemoryLoader 同源取值。
     */
    private String resolveProjectKey() {
        String raw = ConfigUtil.get("project.workspace", System.getProperty("user.dir"));
        if (raw.equals(cachedWorkspaceRaw)) {
            return cachedProjectKey;
        }
        String key;
        try {
            key = Path.of(raw).toRealPath().toString();
        } catch (IOException | RuntimeException e) {
            key = Path.of(raw).toAbsolutePath().normalize().toString();
        }
        cachedWorkspaceRaw = raw;
        cachedProjectKey = key;
        return key;
    }

    /** 本项目可见的行：GLOBAL、未归属（历史遗留空键）或属于当前项目 */
    private static boolean visibleToProject(Memory m, String projectKey) {
        if (m.getScope() == MemoryScope.GLOBAL) {
            return true;
        }
        String key = m.getProjectKey();
        return key == null || key.isBlank() || key.equals(projectKey);
    }

    // ---------------- 写入 ----------------

    /**
     * 显式写入（write-through）：不排队、同步返回记忆 id（RememberTool 契约），但内部复用
     * {@link MemoryConsolidator} 的语义整理决策（不开 LLM 仲裁，避免给请求路径加一次模型调用），
     * 取代旧「相似即覆盖」的 findDuplicate+merge：相关但不同类的内容各自保留，同一事实的修正
     * 才写回旧行，旧值一律归档整理日志。
     * <p>候选层就绪时同指纹的重复写入幂等：直接返回首次写入指向的记忆 id，不重复整理。
     */
    public String remember(Memory memory) {
        if (!isActive() || memory == null
                || memory.getContent() == null || memory.getContent().isBlank()) {
            return null;
        }
        try {
            // 统一盖章项目归属（反思/工具等所有写入口都经过这里）：缺了它，PROJECT 记忆无从判定属于哪个仓库
            if (memory.getProjectKey() == null || memory.getProjectKey().isBlank()) {
                memory.setProjectKey(resolveProjectKey());
            }
            MemoryCandidate cand = MemoryCandidate.fromMemory(memory, MemoryCandidate.SourceType.EXPLICIT);
            cand.setFingerprint(MemoryCandidate.computeFingerprint(cand));
            if (candidateStore != null) {
                if (!candidateStore.insertIfAbsent(cand)) {
                    MemoryCandidate existing = candidateStore.findByFingerprint(cand.getFingerprint());
                    // 同指纹已整理成功且目标记忆仍在：幂等回执，不重复写库
                    if (existing != null && existing.getStatus() == MemoryCandidate.Status.APPLIED
                            && existing.getResultMemoryId() != null
                            && store.findById(existing.getResultMemoryId()) != null) {
                        return existing.getResultMemoryId();
                    }
                    if (existing != null) {
                        // 此前 FAILED/DISCARDED：复用原记录重新整理，保留首次提取的溯源字段
                        cand = existing;
                    }
                }
            }
            MemoryConsolidator.Result r = consolidator.apply(cand, false, null);
            evictIfOverCapacity();
            return r.memoryId;
        } catch (Throwable t) {
            log.error("LongTermMemory: remember failed", t);
            return null;
        }
    }

    /**
     * 批量显式写入，逐条走 {@link #remember(Memory)}。
     */
    public void rememberAll(List<Memory> memories) {
        if (!isActive() || memories == null) {
            return;
        }
        for (Memory m : memories) {
            remember(m);
        }
    }

    /**
     * 容量淘汰：按「当前项目可见集」分桶计数，超过上限时淘汰可见集中最旧的条目
     * （listRecent 为最新在前，尾部即最旧）。全库单一计数会让高频写入的仓库挤掉
     * 其他仓库的记忆，故每次写入只按当前项目可见集（GLOBAL + 本项目 + 未归属行）计数淘汰。
     */
    private void evictIfOverCapacity() {
        if (capacityMax <= 0) {
            return;
        }
        try {
            String projectKey = resolveProjectKey();
            List<Memory> visible = new ArrayList<>();
            for (Memory m : store.listRecent(store.count())) {
                if (visibleToProject(m, projectKey)) {
                    visible.add(m);
                }
            }
            if (visible.size() <= capacityMax) {
                return;
            }
            int removed = 0;
            for (int i = capacityMax; i < visible.size(); i++) {
                store.deleteById(visible.get(i).getId());
                removed++;
            }
            log.info("LongTermMemory: evicted {} oldest visible memories (project={}, capacity={})",
                    removed, projectKey, capacityMax);
        } catch (Throwable t) {
            log.error("LongTermMemory: evict failed", t);
        }
    }

    // ---------------- 检索与注入 ----------------

    /**
     * 按 query 召回相关记忆（作用域不限、不做会话隔离），失败或无结果返回空列表
     */
    public List<Memory> recall(String query) {
        return recall(query, null, topK, null);
    }

    /**
     * 按 query + 作用域召回，limit<=0 时回退默认 topK；scope 为 null 不限作用域。不做会话隔离。
     */
    public List<Memory> recall(String query, MemoryScope scope, int limit) {
        return recall(query, scope, limit, null);
    }

    /**
     * 按 query + 作用域 + 会话召回。sessionId 非空时，SESSION 作用域的记忆仅本会话可见
     * （其他会话的 SESSION 行不泄进 prompt）；为 null/空则不做会话过滤（跨会话管理操作）。
     */
    public List<Memory> recall(String query, MemoryScope scope, int limit, String sessionId) {
        if (!isActive() || !retrievalEnabled || query == null || query.isBlank()) {
            return Collections.emptyList();
        }
        int k = limit > 0 ? limit : topK;
        try {
            // 带上当前项目与会话可见性：其他仓库的 PROJECT/SESSION 记忆、其他会话的 SESSION 记忆
            // 都不得泄进本项目 prompt
            return store.search(query, k, scope, resolveProjectKey(), sessionId);
        } catch (Throwable t) {
            log.error("LongTermMemory: recall failed, query={}", query, t);
            return Collections.emptyList();
        }
    }

    /**
     * 召回并格式化为可注入 prompt 的文本块；无结果返回空串
     */
    public String recallBlock(String query) {
        return recallBlock(query, null, topK, null);
    }

    public String recallBlock(String query, MemoryScope scope, int limit) {
        return recallBlock(query, scope, limit, null);
    }

    public String recallBlock(String query, MemoryScope scope, int limit, String sessionId) {
        List<Memory> hits = recall(query, scope, limit, sessionId);
        return hits.isEmpty() ? "" : format(hits);
    }

    // ---------------- 遗忘 ----------------

    /**
     * 按 id 删除一条记忆，成功返回 true
     */
    public boolean forget(String id) {
        if (!isActive() || id == null || id.isBlank()) {
            return false;
        }
        try {
            store.deleteById(id);
            return true;
        } catch (Throwable t) {
            log.error("LongTermMemory: forget failed, id={}", id, t);
            return false;
        }
    }

    /**
     * 按检索词删除命中的记忆（scope 为 null 不限），返回实际删除条数；不做会话隔离
     */
    public int forgetByQuery(String query, MemoryScope scope, int limit) {
        return forgetByQuery(query, scope, limit, null);
    }

    /**
     * 按检索词删除命中的记忆（带会话可见性，供记忆工具在当前会话内安全操作）
     */
    public int forgetByQuery(String query, MemoryScope scope, int limit, String sessionId) {
        List<Memory> hits = recall(query, scope, limit, sessionId);
        int removed = 0;
        for (Memory m : hits) {
            if (forget(m.getId())) {
                removed++;
            }
        }
        return removed;
    }

    /**
     * 把召回的记忆合并进「本轮要发给模型的消息副本」：
     * 直接拼到首个 system 消息末尾（保持全局仅一条 system，规避 Responses API 的多消息顺序问题），
     * 只改副本、不动 AgentState 的事件真相源。
     *
     * @param callContext 本轮 model.call 的消息列表（会被就地修改）
     * @param query       召回查询（通常是用户本轮输入）
     */
    public void injectRecall(List<MessageItem> callContext, String query) {
        injectRecall(callContext, query, null);
    }

    /**
     * 同 {@link #injectRecall(List, String)}，额外传入当前会话 id：SESSION 作用域的记忆
     * 只在来源会话内注入，其他会话的 SESSION 记忆不得泄进本轮 prompt。
     */
    public void injectRecall(List<MessageItem> callContext, String query, String sessionId) {
        String block = recallBlock(query, null, topK, sessionId);
        if (block.isEmpty() || callContext == null) {
            return;
        }
        if (!callContext.isEmpty() && callContext.get(0) instanceof SystemMessageItem base) {
            callContext.set(0, new SystemMessageItem(base.getContent() + "\n\n" + block));
        } else {
            callContext.add(0, new SystemMessageItem(block));
        }
    }

    // ---------------- 反思抽取 ----------------

    /**
     * 计算一次压缩真正「丢弃」的消息：压缩前上下文里，凡是没有原样出现在压缩后上下文中的对象，
     * 要么被整条删除（L1 摘要吃掉的中段历史），要么被截断替换（L0 清空/截短后换了新对象）——
     * 这些原文在模型上下文里再也看不到，必须在沉入长期记忆前抢救蒸馏一次。
     * <p>按对象引用（而非 equals）比较：压缩保留的尾部消息是同一批对象原样透传，命中即视为保留。
     * 供两条运行时在 {@code maybeCompact} 返回后调用，结果累积到 run 末交给
     * {@link #maybeReflectOnRunEnd} 的抢救通道。
     */
    public static List<MessageItem> collectDiscarded(List<MessageItem> before, List<MessageItem> after) {
        if (before == null || before.isEmpty() || after == null) {
            return Collections.emptyList();
        }
        Set<MessageItem> kept = Collections.newSetFromMap(new IdentityHashMap<>());
        kept.addAll(after);
        List<MessageItem> discarded = new ArrayList<>();
        for (MessageItem m : before) {
            // system 不参与抢救：它是装配出的常驻指令，且压缩前后原样保留
            if (m == null || m instanceof SystemMessageItem) {
                continue;
            }
            if (!kept.contains(m)) {
                discarded.add(m);
            }
        }
        return discarded;
    }

    /**
     * run 结束时按策略决定是否反思（不含抢救素材，保留旧入口兼容测试/调用方）。
     */
    public void maybeReflectOnRunEnd(String sessionId, List<MessageItem> transcript,
                                     Summarizer summarizer, boolean compactionHappened) {
        maybeReflectOnRunEnd(sessionId, transcript, summarizer, compactionHappened, null);
    }

    /**
     * run 结束时决定是否反思：命中 {@link ReflectionPolicy} 后<b>异步</b>蒸馏落库，不阻塞 run 返回路径。
     * <p>额外承担「压缩抢救」：{@code compactionDiscarded} 是本 run 被压缩从上下文里裁掉/截断替换的
     * 原文（{@link #collectDiscarded} 算出）。run 末反思只能看到压缩后的 modelContext，摘要没保留的
     * 信息永久丢失——所以只要真的发生了压缩，丢弃内容就单独提交一次蒸馏，与常规反思的时机门控正交
     * （仅 trigger=off 时整体关闭），落实「压缩丢弃的信息沉淀进长期记忆」的设计初衷。
     * <p>两条通道输入不相交但可能蒸出同一事实（被压缩消息同时也在压缩后上下文周边），候选层的
     * 指纹幂等保证同一事实只正式写入一次（第一阶段遗留的双写代价在此收口）。
     *
     * @param compactionHappened  本 run 是否真的执行了上下文压缩（on_compaction 策略据此判定）
     * @param compactionDiscarded 本 run 被压缩丢弃的消息（可为 null/空）
     */
    public void maybeReflectOnRunEnd(String sessionId, List<MessageItem> transcript,
                                     Summarizer summarizer, boolean compactionHappened,
                                     List<MessageItem> compactionDiscarded) {
        if (!reflectionReady(summarizer)) {
            return;
        }
        if (compactionHappened && compactionDiscarded != null && !compactionDiscarded.isEmpty()
                && reflectionPolicy.shouldRescueOnCompaction()) {
            log.info("LongTermMemory: compaction rescue, distilling {} discarded messages of session {}",
                    compactionDiscarded.size(), sessionId);
            submitReflection(sessionId, compactionDiscarded, summarizer, MemoryCandidate.SourceType.COMPACTION_RESCUE);
        }
        if (!reflectionPolicy.shouldReflectOnRunEnd(sessionId, compactionHappened)) {
            log.debug("LongTermMemory: reflection skipped by policy (trigger={}, compaction={})",
                    reflectionPolicy.getTrigger(), compactionHappened);
            return;
        }
        submitReflection(sessionId, transcript, summarizer, MemoryCandidate.SourceType.RUN_REFLECTION);
    }

    /**
     * 会话结束（REPL 退出 / 切换 session / --prompt 一次性执行完）时触发：仅 session_end 策略生效。
     * <p>此处<b>同步</b>执行——会话已终止，阻塞等待蒸馏落库才能保证不丢，且无后续对话受影响。
     */
    public void maybeReflectOnSessionEnd(String sessionId, List<MessageItem> transcript, Summarizer summarizer) {
        if (!reflectionReady(summarizer) || !reflectionPolicy.shouldReflectOnSessionEnd()) {
            return;
        }
        // 同步快照后直接蒸馏：transcript 传副本，规避调用方随后清空/复用上下文
        doReflect(sessionId, new ArrayList<>(transcript), summarizer, MemoryCandidate.SourceType.SESSION_END);
    }

    /**
     * 立即同步蒸馏并整理落库（不经策略门控）。保留给测试或需要强制反思的场景；
     * 运行时主流程请改用 {@link #maybeReflectOnRunEnd} / {@link #maybeReflectOnSessionEnd}。
     */
    public void reflectAndStore(String sessionId, List<MessageItem> transcript, Summarizer summarizer) {
        if (!reflectionReady(summarizer)) {
            return;
        }
        doReflect(sessionId, new ArrayList<>(transcript), summarizer, MemoryCandidate.SourceType.RUN_REFLECTION);
    }

    /** 反思是否具备执行条件：记忆可用 + 反思开关开 + 有摘要器 */
    private boolean reflectionReady(Summarizer summarizer) {
        return isActive() && reflectionEnabled && summarizer != null && consolidator != null;
    }

    /**
     * 把反思任务提交到后台单线程执行器。<b>提交时即对 transcript 做快照</b>，
     * 否则异步任务真正跑起来时，主线程可能已进入下一轮并 replaceModelContext，读到被改动的上下文。
     */
    private void submitReflection(String sessionId, List<MessageItem> transcript, Summarizer summarizer,
                                  MemoryCandidate.SourceType sourceType) {
        final List<MessageItem> snapshot = new ArrayList<>(transcript);
        try {
            ensureExecutor().submit(() -> doReflect(sessionId, snapshot, summarizer, sourceType));
        } catch (Throwable t) {
            // 执行器已关闭（如正在退出）等：降级为 no-op，绝不打断主流程
            // 候选未落库即无任何痕迹，但反思本身丢失可接受——下一次抢救/反思会重新看到这些信息
            log.warn("LongTermMemory: submit reflection failed, sessionId={} ({})", sessionId, t.toString());
        }
    }

    /**
     * 反思本体：蒸馏成候选→指纹幂等落库→逐条交给整理器落正式库；任何异常吞掉，绝不外抛。
     * <p>候选层就绪时重复指纹的候选直接跳过（不重复整理）；候选层降级（candidateStore=null）时
     * 不持久化候选、不写整理日志，提取结果仍过整理器落库——kill switch 只关「候选记录」不回滚
     * 第一阶段的语义整理能力。
     */
    private void doReflect(String sessionId, List<MessageItem> transcript, Summarizer summarizer,
                           MemoryCandidate.SourceType sourceType) {
        try {
            List<MemoryCandidate> candidates = new MemoryExtractor(summarizer).extract(sessionId, transcript, sourceType);
            for (MemoryCandidate cand : candidates) {
                if (cand.getProjectKey() == null || cand.getProjectKey().isBlank()) {
                    cand.setProjectKey(resolveProjectKey());
                }
                cand.setFingerprint(MemoryCandidate.computeFingerprint(cand));
                if (candidateStore != null && !candidateStore.insertIfAbsent(cand)) {
                    log.debug("LongTermMemory: duplicate candidate skipped by fingerprint, session={}", sessionId);
                    continue;
                }
                consolidator.apply(cand, true, summarizer);
            }
            if (candidateStore != null) {
                candidateStore.pruneProcessed(candidateKeepRecent);
            }
            // 整理器直接写正式库不经 remember，淘汰在此统一补做（旧路径由 rememberAll→remember 承担）
            evictIfOverCapacity();
        } catch (Throwable t) {
            log.error("LongTermMemory: reflect failed, sessionId={}", sessionId, t);
        }
    }

    private ExecutorService ensureExecutor() {
        ExecutorService ex = reflectionExecutor;
        if (ex == null) {
            synchronized (this) {
                ex = reflectionExecutor;
                if (ex == null) {
                    ex = Executors.newSingleThreadExecutor(r -> {
                        Thread t = new Thread(r, "ltm-reflection");
                        t.setDaemon(true);
                        return t;
                    });
                    reflectionExecutor = ex;
                }
            }
        }
        return ex;
    }

    /**
     * 等待在途异步反思任务排空（JVM shutdown hook 调用）：优雅关闭 + 限时等待，超时则强关。
     * 幂等：执行器为 null（从未提交过反思）时也要 drain——候选可能由更早的提交落库后
     * 因异常停在 PENDING，退出前再兜底整理一次。
     */
    public void awaitPendingReflection() {
        ExecutorService ex = reflectionExecutor;
        if (ex != null) {
            ex.shutdown();
            try {
                if (!ex.awaitTermination(reflectionShutdownTimeoutSeconds, TimeUnit.SECONDS)) {
                    log.warn("LongTermMemory: reflection drain timed out after {}s, forcing shutdown",
                            reflectionShutdownTimeoutSeconds);
                    ex.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                ex.shutdownNow();
            }
        }
        drainPendingCandidates();
    }

    /**
     * 兑底 drain：把停留在 PENDING 的候选（提交失败/整理异常等残留）整理一次。
     * <p>此处不开 LLM 仲裁：退出路径上没有反思请求的 Summarizer 引用，且中区间启发式已够安全；
     * 仍失败的候选会停在 FAILED，下次进程启动后的写入/反思不重复处理已终态记录。
     */
    private void drainPendingCandidates() {
        if (candidateStore == null || consolidator == null) {
            return;
        }
        try {
            List<MemoryCandidate> pending = candidateStore.listPending(200);
            if (pending.isEmpty()) {
                return;
            }
            log.info("LongTermMemory: draining {} pending candidates", pending.size());
            for (MemoryCandidate cand : pending) {
                consolidator.apply(cand, false, null);
            }
        } catch (Throwable t) {
            log.warn("LongTermMemory: candidate drain failed ({})", t.toString());
        }
    }

    // ---------------- 展示格式 ----------------

    public String format(List<Memory> hits) {
        StringBuilder sb = new StringBuilder();
        sb.append("===== 相关长期记忆 (top ").append(hits.size()).append(") =====\n");
        int i = 1;
        for (Memory m : hits) {
            sb.append(i++).append(". [").append(m.getCategory()).append("|").append(m.getScope()).append("] ");
            if (m.getTitle() != null && !m.getTitle().isBlank()) {
                sb.append(m.getTitle()).append("\n");
            } else {
                sb.append("\n");
            }
            sb.append(m.getContent().strip()).append("\n");
            if (m.getKeywords() != null && !m.getKeywords().isEmpty()) {
                sb.append("关键词: ").append(String.join(", ", m.getKeywords())).append("\n");
            }
            if (i <= hits.size()) {
                sb.append("\n");
            }
        }
        sb.append("==============================");
        return sb.toString();
    }
}
