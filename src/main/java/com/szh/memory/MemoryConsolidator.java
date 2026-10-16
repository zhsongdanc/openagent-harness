package com.szh.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.szh.context.compaction.Summarizer;
import com.szh.context.dto.MessageItem;
import com.szh.context.dto.SystemMessageItem;
import com.szh.context.dto.UserMessageItem;
import com.szh.utils.JsonUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 记忆整理器（L4 第二阶段核心）：把「候选记忆」与正式记忆库中既有条目的关系判定成
 * 六种动作之一（{@link ConsolidationAction}）再落库，取代第一阶段「相似即覆盖」的单值 merge。
 * <p>决策流程：拉可见相似候选（继承第一阶段的项目/会话过滤）→ {@link MemorySimilarity} 取最高分
 * 按两档阈值分桶：
 * <ul>
 *   <li>≥ high（默认 0.92）：近重复——规范化正文相等 SKIP，否则 UPDATE；</li>
 *   <li>[mid, high)（mid 复用 memory.dedup.threshold=0.72）：「相关但未必同一事实」的危险区间——
 *       自动异步通道交给 LLM 仲裁（update/merge/supersede/add/discard），显式同步通道或仲裁失败
 *       回退启发式（同 category→UPDATE，异 category→ADD）；</li>
 *   <li>&lt; mid：ADD。</li>
 * </ul>
 * <p>不变式：任何一步失败都静默降级（更保守的动作或 FAILED 状态），绝不向调用方外抛——
 * 整理发生在后台反思线程与工具写入路径上，抛异常会拖垮 run（铁律 6）。
 * UPDATE/MERGE/SUPERSEDE 执行前把旧记忆 JSON 归档进整理日志，保证"取代可追溯"。
 *
 * @author demussong
 * @date 2026-10-16
 */
@Slf4j
public class MemoryConsolidator {

    /** 仲裁指令：只允许描述既有记忆与候选之间的关系并给动作，不允许引入对话外的新信息 */
    private static final String ARBITRATION_PROMPT = """
            你是记忆整理裁决器。给定一条[既有记忆]和一条[新候选记忆]（二者文本相近、可能相关），\
            判断关系并输出唯一处理动作，不要输出任何解释文字或 Markdown 围栏。
            动作含义：
            - update：新候选是对既有记忆同一事实的修正/改口，输出修正后的完整 title 与 content
            - merge：两条互补（新候选细化而非否定既有记忆），输出合并后的完整 title 与 content
            - supersede：新候选是与既有记忆冲突的新事实（如技术栈迁移），旧记忆应被取代，输出新事实的 title 与 content
            - add：主题其实不同，只是文本相似，应各自独立保留
            - discard：新候选没有长期保存价值，或对既有记忆毫无信息增量
            只输出一个 JSON 对象：{"action":"update|merge|supersede|add|discard","title":"...","content":"...","reason":"一句话理由"}
            update/merge/supersede 必须给出完整自包含的 title/content；add/discard 可省略。
            """;

    private final MemoryStore store;
    /** 候选层存储，null 表示候选层未启用：仍做语义整理，但不写日志、不回写候选终态 */
    private final CandidateStore candidateStore;
    /** 去重总开关：关闭时不拉相似候选，所有候选直接 ADD（兼容旧 memory.dedup.enabled=false 语义） */
    private final boolean dedupEnabled;
    /** [mid, high) 危险区间下界：复用第一阶段的去重阈值，低于它视为不相关 */
    private final double midThreshold;
    /** 近重复档下界 */
    private final double highThreshold;
    /** 去重候选拉取条数 */
    private final int dedupCandidates;
    /** 是否允许 LLM 仲裁总开关（还只有自动异步通道会传 allowLlm=true，显式同步路径恒不仲裁） */
    private final boolean llmEnabled;

    public MemoryConsolidator(MemoryStore store, CandidateStore candidateStore,
                              boolean dedupEnabled, double midThreshold, double highThreshold,
                              int dedupCandidates, boolean llmEnabled) {
        this.store = store;
        this.candidateStore = candidateStore;
        this.dedupEnabled = dedupEnabled;
        this.midThreshold = midThreshold;
        this.highThreshold = highThreshold;
        this.dedupCandidates = dedupCandidates;
        this.llmEnabled = llmEnabled;
    }

    /** 整理结果：动作 + 落库后指向的正式记忆 id（DISCARD 为 null）+ 备注 + 候选终态 */
    public static class Result {
        public final ConsolidationAction action;
        public final String memoryId;
        public final String note;
        public final MemoryCandidate.Status status;

        public Result(ConsolidationAction action, String memoryId, String note, MemoryCandidate.Status status) {
            this.action = action;
            this.memoryId = memoryId;
            this.note = note;
            this.status = status;
        }
    }

    /**
     * 对一条候选做关系判定并执行。
     *
     * @param allowLlmArbitration 是否允许 mid 区间 LLM 仲裁（仅自动异步通道；显式同步写入要即时返回，不额外加一次模型调用）
     * @param summarizer          仲裁用的模型适配器，null 视为不允许
     * @return 整理结果；任何内部异常都收敛为 FAILED，不外抛
     */
    public Result apply(MemoryCandidate cand, boolean allowLlmArbitration, Summarizer summarizer) {
        Result r;
        try {
            r = decideAndExecute(cand, allowLlmArbitration && llmEnabled && summarizer != null, summarizer);
        } catch (Throwable t) {
            log.error("MemoryConsolidator: apply failed, candidate={}", cand == null ? null : cand.getId(), t);
            r = new Result(null, null, "整理异常: " + t, MemoryCandidate.Status.FAILED);
        }
        finishCandidate(cand, r);
        return r;
    }

    // ---------------- 决策 ----------------

    private Result decideAndExecute(MemoryCandidate cand, boolean useLlm, Summarizer summarizer) {
        if (cand == null || cand.getContent() == null || cand.getContent().isBlank()) {
            return discard(cand, "正文为空");
        }
        // 低可信自动候选直接丢弃：模型从对话里"猜"出来的含糊习惯不值得进正式库；
        // 显式写入（用户/模型明说记住）不受此限
        if (cand.getConfidence() == MemoryCandidate.Confidence.LOW
                && cand.getSourceType() != MemoryCandidate.SourceType.EXPLICIT) {
            return discard(cand, "低可信度自动候选，不写入正式库");
        }

        Memory incoming = cand.toMemory();
        Memory best = null;
        double bestScore = 0d;
        if (dedupEnabled) {
            try {
                List<Memory> candidates = store.search(buildQuery(cand), dedupCandidates,
                        cand.getScope(), cand.getProjectKey(), cand.getSourceSessionId());
                for (Memory c : candidates) {
                    double score = MemorySimilarity.similarity(incoming, c);
                    if (score > bestScore) {
                        bestScore = score;
                        best = c;
                    }
                }
            } catch (Throwable t) {
                // 拉不到候选不能阻断写入：宁可多一条近似记忆（后续可人工/第三阶段整理），不可丢信息
                log.warn("MemoryConsolidator: fetch similar failed, treat as new. ({})", t);
            }
        }

        if (best == null || bestScore < midThreshold) {
            return executeAdd(cand, incoming, bestScore);
        }
        if (bestScore >= highThreshold) {
            // 近重复档：完全相同只记幂等指向，否则按"同一事实的修正"处理
            boolean identical = MemorySimilarity.normalize(best.getContent())
                    .equals(MemorySimilarity.normalize(incoming.getContent()));
            return identical
                    ? new Result(ConsolidationAction.SKIP, best.getId(),
                    String.format("完全重复（score=%.3f），指向既有记忆", bestScore), MemoryCandidate.Status.APPLIED)
                    : overwriteExisting(cand, best, incoming, null, null, ConsolidationAction.UPDATE, bestScore);
        }
        // mid 危险区间：文本相关 ≠ 语义同一，先尝试仲裁，失败回退启发式
        if (useLlm) {
            Result arbitrated = arbitrate(cand, best, incoming, bestScore, summarizer);
            if (arbitrated != null) {
                return arbitrated;
            }
        }
        // 启发式回退：同分类视为同一事实的修正（≈旧 merge 行为，但旧值先归档日志），
        // 不同分类更可能是"相关的两件事"，各自保留
        if (sameCategory(best, incoming)) {
            return overwriteExisting(cand, best, incoming, null, null, ConsolidationAction.UPDATE, bestScore);
        }
        return executeAdd(cand, incoming, bestScore);
    }

    // ---------------- LLM 仲裁 ----------------

    /**
     * 把既有记忆与新候选交给模型裁决关系；输出非法/动作无法执行时返回 null 由调用方回退启发式。
     */
    private Result arbitrate(MemoryCandidate cand, Memory best, Memory incoming,
                             double score, Summarizer summarizer) {
        try {
            List<MessageItem> request = new ArrayList<>();
            request.add(new SystemMessageItem(ARBITRATION_PROMPT));
            request.add(new UserMessageItem("既有记忆：\n" + JsonUtil.toJson(best)
                    + "\n\n新候选记忆：\n" + JsonUtil.toJson(incoming)
                    + "\n\n相似度：" + String.format("%.3f", score)));
            String output = summarizer.summarize(request);
            if (output == null || output.isBlank()) {
                return null;
            }
            JsonNode decision = extractJsonObject(output);
            if (decision == null) {
                log.warn("MemoryConsolidator: arbitration output not JSON, fallback to heuristic");
                return null;
            }
            ConsolidationAction action = ConsolidationAction.from(text(decision, "action"));
            if (action == null) {
                return null;
            }
            String reason = text(decision, "reason");
            String newContent = text(decision, "content");
            String newTitle = text(decision, "title");
            return switch (action) {
                case UPDATE, MERGE -> overwriteExisting(cand, best, incoming,
                        blankToNull(newContent), blankToNull(newTitle), action, score);
                case SUPERSEDE -> {
                    // 取代后的新事实以仲裁文本为准（它才是裁决出的"新真相"），模型没给则回退候选原文
                    if (blankToNull(newContent) != null) {
                        incoming.setContent(newContent.trim());
                    }
                    if (blankToNull(newTitle) != null) {
                        incoming.setTitle(newTitle.trim());
                    }
                    yield executeSupersede(cand, best, incoming, reason, score);
                }
                case ADD -> executeAdd(cand, incoming, score);
                case DISCARD -> discard(cand, "仲裁判定丢弃: " + nullToEmpty(reason));
                // 仲裁器不该给 SKIP，但给了也合理（判定完全重复）——与代码判重同路
                case SKIP -> new Result(ConsolidationAction.SKIP, best.getId(),
                        "仲裁判定完全重复: " + nullToEmpty(reason), MemoryCandidate.Status.APPLIED);
            };
        } catch (Throwable t) {
            log.warn("MemoryConsolidator: arbitration failed, fallback to heuristic. ({})", t);
            return null;
        }
    }

    // ---------------- 动作执行 ----------------

    private Result executeAdd(MemoryCandidate cand, Memory incoming, double score) {
        store.save(incoming);
        addLog(cand, ConsolidationAction.ADD, incoming.getId(), null,
            String.format("无可靠既有记忆（最高相似度 %.3f），新增", score));
        return new Result(ConsolidationAction.ADD, incoming.getId(), null, MemoryCandidate.Status.APPLIED);
    }

    /**
     * UPDATE/MERGE 共用：保留旧 id 与 createdAt 写回既有行。
     * contentOverride/titleOverride 来自仲裁文本，为空回退候选自身值（与旧 merge 语义一致）。
     */
    private Result overwriteExisting(MemoryCandidate cand, Memory best, Memory incoming,
                                     String contentOverride, String titleOverride,
                                     ConsolidationAction action, double score) {
        Memory merged = new Memory();
        merged.setId(best.getId());
        merged.setCreatedAt(best.getCreatedAt());
        merged.setContent(contentOverride != null ? contentOverride : incoming.getContent());
        merged.setTitle(titleOverride != null ? titleOverride
                : (incoming.getTitle() != null && !incoming.getTitle().isBlank() ? incoming.getTitle() : best.getTitle()));
        merged.setCategory(incoming.getCategory() != null ? incoming.getCategory() : best.getCategory());
        merged.setScope(best.getScope() != null ? best.getScope() : incoming.getScope());
        merged.setKeywords(unionKeywords(best.getKeywords(), incoming.getKeywords()));
        merged.setSourceSessionId(incoming.getSourceSessionId() != null
                ? incoming.getSourceSessionId() : best.getSourceSessionId());
        // 项目归属：旧行已有主则继承（同项目才可能命中判重），遗留空键收编为写入方项目
        merged.setProjectKey(best.getProjectKey() != null && !best.getProjectKey().isBlank()
                ? best.getProjectKey() : incoming.getProjectKey());
        String before = JsonUtil.toJson(best);
        store.save(merged);
        addLog(cand, action, merged.getId(), before, String.format("写回既有记忆（相似度 %.3f）", score));
        return new Result(action, merged.getId(), null, MemoryCandidate.Status.APPLIED);
    }

    /**
     * SUPERSEDE：旧行 JSON 归档进整理日志后删除，插入新行（新 id）——
     * "旧记录仍可追溯"落在日志表而非给 FTS5 加状态列，避免整表重建迁移。
     */
    private Result executeSupersede(MemoryCandidate cand, Memory best, Memory incoming, String reason, double score) {
        String before = JsonUtil.toJson(best);
        store.deleteById(best.getId());
        store.save(incoming);
        addLog(cand, ConsolidationAction.SUPERSEDE, incoming.getId(), before,
                "新事实取代旧记忆：" + nullToEmpty(reason) + String.format("（相似度 %.3f）", score));
        return new Result(ConsolidationAction.SUPERSEDE, incoming.getId(), null, MemoryCandidate.Status.APPLIED);
    }

    private Result discard(MemoryCandidate cand, String note) {
        addLog(cand, ConsolidationAction.DISCARD, null, null, note);
        return new Result(ConsolidationAction.DISCARD, null, note, MemoryCandidate.Status.DISCARDED);
    }

    // ---------------- 收尾与工具方法 ----------------

    /** 回写候选终态；candidateStore 为 null（候选层降级）时直接跳过 */
    private void finishCandidate(MemoryCandidate cand, Result r) {
        if (candidateStore == null || cand == null) {
            return;
        }
        try {
            cand.setStatus(r.status);
            cand.setDecision(r.action);
            cand.setResultMemoryId(r.memoryId);
            if (r.note != null) {
                cand.setNote(r.note);
            }
            cand.setProcessedAt(System.currentTimeMillis());
            candidateStore.save(cand);
        } catch (Throwable t) {
            log.warn("MemoryConsolidator: finish candidate failed, id={} ({})", cand.getId(), t);
        }
    }

    private void addLog(MemoryCandidate cand, ConsolidationAction action, String memoryId,
                        String beforeSnapshot, String note) {
        if (candidateStore == null) {
            return;
        }
        try {
            ConsolidationLogEntry entry = new ConsolidationLogEntry();
            entry.setCandidateId(cand == null ? null : cand.getId());
            entry.setFingerprint(cand == null ? null : cand.getFingerprint());
            entry.setAction(action);
            entry.setMemoryId(memoryId);
            entry.setBeforeSnapshot(beforeSnapshot);
            entry.setNote(note);
            entry.setCreatedAt(System.currentTimeMillis());
            candidateStore.appendLog(entry);
        } catch (Throwable t) {
            log.warn("MemoryConsolidator: append log failed, action={} ({})", action, t);
        }
    }

    /**
     * 构造相似候选检索词：标题 + 关键词 + 正文前 60 字（自第一阶段 findDuplicate 迁入）
     */
    private static String buildQuery(MemoryCandidate m) {
        StringBuilder sb = new StringBuilder();
        if (m.getTitle() != null) {
            sb.append(m.getTitle()).append(' ');
        }
        if (m.getKeywords() != null && !m.getKeywords().isEmpty()) {
            sb.append(String.join(" ", m.getKeywords())).append(' ');
        }
        String content = m.getContent() == null ? "" : m.getContent().trim();
        sb.append(content.length() > 60 ? content.substring(0, 60) : content);
        return sb.toString().trim();
    }

    private static List<String> unionKeywords(List<String> a, List<String> b) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (List<String> src : List.of(a == null ? List.<String>of() : a, b == null ? List.<String>of() : b)) {
            for (String s : src) {
                if (s != null && !s.isBlank()) {
                    set.add(s.trim());
                }
            }
        }
        return new ArrayList<>(set);
    }

    private static boolean sameCategory(Memory a, Memory b) {
        MemoryCategory ca = a.getCategory() == null ? MemoryCategory.OTHER : a.getCategory();
        MemoryCategory cb = b.getCategory() == null ? MemoryCategory.OTHER : b.getCategory();
        return ca == cb;
    }

    /** 从模型输出截取第一个 JSON 对象（容忍围栏/前后解释文字） */
    private static JsonNode extractJsonObject(String output) {
        String t = output.trim();
        int start = t.indexOf('{');
        int end = t.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return JsonUtil.readTree(t.substring(start, end + 1));
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
