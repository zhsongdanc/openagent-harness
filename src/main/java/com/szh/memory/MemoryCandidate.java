package com.szh.memory;

import lombok.Data;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * 记忆候选（L4 第二阶段）：自动反思/抢救通道从对话中提取出的「待整理」信息，
 * 经 {@link MemoryConsolidator} 决策后才进入正式记忆库；显式 remember 走 write-through
 * 也复用同一记录做溯源。
 * <p>定位是<b>派生层的过渡数据</b>，不是事件真相源——不进 EventStore、不新增事件类型：
 * 记忆提取依赖非确定性 LLM 调用，若作为事件会在回放时重复写入甚至产出版本分歧。
 * <p>关键治理字段：
 * <ul>
 *   <li>{@link #fingerprint}：内容幂等标识（正文规范化 + 分类 + 作用域 + 项目），同一事实被
 *       抢救与 run 末反思两条通道各蒸馏一次时只正式写入一次；<b>故意不含 sourceType</b>，
 *       否则跨通道幂等失效；SESSION 作用域例外地掺入会话 id——同事实跨会话是两个独立的
 *       临时结论，必须各自成条（与存储层的会话隔离边界保持一致）；</li>
 *   <li>{@link #evidence}：一句话提取依据，排查"这条错误记忆从哪来"的主要线索；</li>
 *   <li>{@link #confidence}：LOW 的自动候选直接 DISCARD，显式写入恒为 HIGH（用户明示即最高可信）。</li>
 * </ul>
 *
 * @author demussong
 * @date 2026-10-16
 */
@Data
public class MemoryCandidate {

    /** 候选来源通道：溯源与低可信丢弃策略据此区分显式/自动 */
    public enum SourceType {
        /** 压缩丢弃内容抢救蒸馏 */
        COMPACTION_RESCUE,
        /** run 结束按策略反思 */
        RUN_REFLECTION,
        /** 会话结束兜底反思 */
        SESSION_END,
        /** 模型/用户经 remember 工具显式写入 */
        EXPLICIT,
        ;

        public static SourceType from(String value) {
            if (value == null || value.isBlank()) {
                return RUN_REFLECTION;
            }
            try {
                return valueOf(value.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return RUN_REFLECTION;
            }
        }
    }

    /** 提取可信度：由抽取模型自评（显式写入固定 HIGH），LOW 的自动候选不入正式库 */
    public enum Confidence {
        HIGH,
        MEDIUM,
        LOW,
        ;

        /** 宽松解析，缺失/非法按 MEDIUM——模型漏填字段不应导致候选被误丢 */
        public static Confidence from(String value) {
            if (value == null || value.isBlank()) {
                return MEDIUM;
            }
            switch (value.trim().toLowerCase()) {
                case "high":
                case "high_confidence":
                    return HIGH;
                case "low":
                    return LOW;
                default:
                    return MEDIUM;
            }
        }
    }

    /** 处理状态：PENDING 待整理；APPLIED 已落正式库（含 SKIP 命中既有记忆）；DISCARDED 被丢弃；FAILED 整理异常 */
    public enum Status {
        PENDING, APPLIED, DISCARDED, FAILED,
        ;

        public static Status from(String value) {
            if (value == null || value.isBlank()) {
                return PENDING;
            }
            try {
                return valueOf(value.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return PENDING;
            }
        }
    }

    private String id;

    /** 内容幂等标识，见类注释 */
    private String fingerprint;

    // ---- 记忆载荷（与 Memory 对应）----
    private String title;
    private String content;
    private MemoryCategory category = MemoryCategory.OTHER;
    private MemoryScope scope = MemoryScope.PROJECT;
    private List<String> keywords = new ArrayList<>();

    // ---- 溯源 ----
    private SourceType sourceType = SourceType.RUN_REFLECTION;
    /** 一句话提取依据（"用户在后段纠正了回答风格"），来自抽取模型的自述 */
    private String evidence;
    private Confidence confidence = Confidence.MEDIUM;
    private String projectKey;
    private String sourceSessionId;

    // ---- 治理 ----
    private Status status = Status.PENDING;
    /** 整理决策结果 */
    private ConsolidationAction decision;
    /** 整理后指向的正式记忆 id（DISCARD 时为空） */
    private String resultMemoryId;
    /** 决策备注/丢弃原因 */
    private String note;

    private long createdAt;
    private long processedAt;

    /**
     * 计算内容幂等指纹：sha256(规范化正文|分类|作用域|项目[|会话 id])。
     * <p>GLOBAL/PROJECT 不掺会话 id：同一事实在抢救与反思两通道各蒸一次必须命中幂等；
     * SESSION 作用域要掺：它的可见性边界就是会话，同文本跨会话是两个独立结论（否则第二个
     * 会话写入会被幂等吞掉、错误回执另一会话的记忆 id）。规范化复用
     * {@link MemorySimilarity#normalize}（仅留字母数字），让标点/空白差异不破坏幂等；
     * 摘要算法异常理论上不会发生（JDK 自带 SHA-256），兜底退化为 hashCode 串仍可满足 UNIQUE 幂等语义。
     */
    public static String computeFingerprint(MemoryCandidate c) {
        String raw = MemorySimilarity.normalize(c.getContent())
                + '|' + (c.getCategory() == null ? MemoryCategory.OTHER : c.getCategory())
                + '|' + (c.getScope() == null ? MemoryScope.PROJECT : c.getScope())
                + '|' + (c.getProjectKey() == null ? "" : c.getProjectKey());
        if (c.getScope() == MemoryScope.SESSION) {
            raw += '|' + (c.getSourceSessionId() == null ? "" : c.getSourceSessionId());
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(raw.hashCode());
        }
    }

    /**
     * 转成正式记忆快照：供相似度打分（{@link MemorySimilarity#similarity} 只认 Memory）与 ADD 落库。
     * 不携带 id/时间戳——正式行身份由整理器按动作决定（新建 or 复用旧 id）。
     */
    public Memory toMemory() {
        Memory m = new Memory();
        m.setTitle(title);
        m.setContent(content);
        m.setCategory(category == null ? MemoryCategory.OTHER : category);
        m.setScope(scope == null ? MemoryScope.PROJECT : scope);
        m.setKeywords(keywords == null ? new ArrayList<>() : new ArrayList<>(keywords));
        m.setProjectKey(projectKey);
        m.setSourceSessionId(sourceSessionId);
        return m;
    }

    /** 从正式记忆构造候选（显式 remember 的 write-through 入口用） */
    public static MemoryCandidate fromMemory(Memory m, SourceType sourceType) {
        MemoryCandidate c = new MemoryCandidate();
        c.setTitle(m.getTitle());
        c.setContent(m.getContent());
        c.setCategory(m.getCategory());
        c.setScope(m.getScope());
        c.setKeywords(m.getKeywords());
        c.setProjectKey(m.getProjectKey());
        c.setSourceSessionId(m.getSourceSessionId());
        c.setSourceType(sourceType);
        // 用户/模型明示的写入即最高可信，不参与低置信丢弃
        c.setConfidence(Confidence.HIGH);
        return c;
    }
}
