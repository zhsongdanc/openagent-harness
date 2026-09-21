package com.szh.memory;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 记忆相似度计算：为「写入去重/近似合并」提供确定性打分，不依赖额外模型调用。
 * <p>
 * 对中文（CJK）友好的做法是把文本归一化后切成<b>字符二元组（bigram）</b>再算 Jaccard，
 * 避免英文分词器对连续汉字无效的问题；同时辅以标题相似度与关键词重叠度。
 * 得分范围 [0,1]，越大越相似，由调用方与阈值比较决定是否判为重复。
 *
 * @author demussong
 * @date 2026/9/21
 */
public final class MemorySimilarity {

    /** 内容为主、标题/关键词为辅的基础权重（仅对双方都具备的信号加权，再按权重和归一化） */
    private static final double W_CONTENT = 0.7;
    private static final double W_TITLE = 0.2;
    private static final double W_KEYWORD = 0.1;

    private MemorySimilarity() {
    }

    /**
     * 综合相似度：以内容 bigram Jaccard 为主信号，标题相似与关键词重叠作为增强项。
     * <p>
     * 关键点：<b>只对双方都存在该字段时计入对应权重，并按已计入权重之和归一化</b>，
     * 避免「标题缺失」把总分硬拉低到永远达不到阈值（内容才是必填主信号）。
     */
    public static double similarity(Memory a, Memory b) {
        if (a == null || b == null) {
            return 0d;
        }
        double content = bigramJaccard(normalize(a.getContent()), normalize(b.getContent()));
        double acc = W_CONTENT * content;
        double weightSum = W_CONTENT;

        if (hasText(a.getTitle()) && hasText(b.getTitle())) {
            acc += W_TITLE * bigramJaccard(normalize(a.getTitle()), normalize(b.getTitle()));
            weightSum += W_TITLE;
        }
        if (hasKeywords(a.getKeywords()) && hasKeywords(b.getKeywords())) {
            acc += W_KEYWORD * keywordOverlap(a.getKeywords(), b.getKeywords());
            weightSum += W_KEYWORD;
        }
        return weightSum == 0d ? 0d : acc / weightSum;
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static boolean hasKeywords(List<String> keywords) {
        return keywords != null && !keywords.isEmpty();
    }

    /**
     * 归一化：转小写，仅保留字母/数字/CJK，去掉空白与标点，降低表面差异带来的干扰
     */
    static String normalize(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = Character.toLowerCase(s.charAt(i));
            if (Character.isLetterOrDigit(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 字符二元组集合的 Jaccard 相似度；长度不足 2 时退化为整串单元素集合比较
     */
    static double bigramJaccard(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0d;
        }
        Set<String> sa = bigrams(a);
        Set<String> sb = bigrams(b);
        Set<String> inter = new HashSet<>(sa);
        inter.retainAll(sb);
        Set<String> union = new HashSet<>(sa);
        union.addAll(sb);
        return union.isEmpty() ? 0d : (double) inter.size() / union.size();
    }

    private static Set<String> bigrams(String s) {
        Set<String> grams = new HashSet<>();
        if (s.length() < 2) {
            grams.add(s);
            return grams;
        }
        for (int i = 0; i + 2 <= s.length(); i++) {
            grams.add(s.substring(i, i + 2));
        }
        return grams;
    }

    /**
     * 关键词重叠度：归一化后按集合 Jaccard 计算
     */
    static double keywordOverlap(List<String> ka, List<String> kb) {
        Set<String> sa = normKeywords(ka);
        Set<String> sb = normKeywords(kb);
        if (sa.isEmpty() || sb.isEmpty()) {
            return 0d;
        }
        Set<String> inter = new HashSet<>(sa);
        inter.retainAll(sb);
        Set<String> union = new HashSet<>(sa);
        union.addAll(sb);
        return union.isEmpty() ? 0d : (double) inter.size() / union.size();
    }

    private static Set<String> normKeywords(List<String> keywords) {
        Set<String> set = new HashSet<>();
        if (keywords == null) {
            return set;
        }
        for (String k : keywords) {
            String n = normalize(k);
            if (!n.isEmpty()) {
                set.add(n);
            }
        }
        return set;
    }
}
