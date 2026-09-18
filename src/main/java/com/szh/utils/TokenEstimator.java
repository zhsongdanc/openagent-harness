package com.szh.utils;

/**
 * 简易 token 数估算工具。
 * <p>
 * 用于在调用 model 之前快速判断当前上下文大约消耗多少 token，
 * 决定是否触发压缩。精确值以服务端返回的 usage.prompt_tokens 为准。
 * <p>
 * 估算规则（粗略）：
 * - 中文字符：约 1.5 字 / token
 * - 英文/数字/ASCII：约 4 字符 / token
 * - 标点符号、空白按 1 token / 4 字符计算
 *
 * @author demussong
 * @date 2026/9/17
 */
public class TokenEstimator {

    private TokenEstimator() {
    }

    /**
     * 估算一段文本的 token 数
     */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int tokens = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCjk(c)) {
                // 中文字符约 1.5 字/token，即每个中文字约 0.67 token，向上取整为 1
                tokens += 1;
            } else {
                // ASCII 字符约 4 字符/token
                tokens += 1;
            }
        }
        // ASCII 字符按 4:1 折算
        int asciiCount = 0;
        int cjkCount = 0;
        for (int i = 0; i < text.length(); i++) {
            if (isCjk(text.charAt(i))) {
                cjkCount++;
            } else {
                asciiCount++;
            }
        }
        // 重新计算：中文 1字≈1token，英文 4字符≈1token
        return cjkCount + Math.max(1, asciiCount / 4);
    }

    /**
     * 判断字符是否为 CJK（中日韩）字符
     */
    private static boolean isCjk(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
                || block == Character.UnicodeBlock.HIRAGANA
                || block == Character.UnicodeBlock.KATAKANA;
    }
}
