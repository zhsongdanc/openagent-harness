package com.szh.utils;

import java.util.function.IntFunction;

/**
 * @author demussong
 * @describe
 * @date 2026/8/27 14:22
 */
public class CommonUtils {

    public static String generateId() {
        return java.util.UUID.randomUUID().toString().replaceAll("-","");
    }

    /**
     * 头尾保留式截断：保留开头约 2/3、结尾约 1/3，中间用 markerBuilder 生成的标记替代。
     * <p>
     * 参考主流 harness（Claude Code / Codex）对超长工具输出的处理——头尾通常包含最有价值的信息
     * （命令回显、报错摘要、最终结论），中间细节按需通过其他通道（如 read_tool_result）回读。
     * 抽为公共方法，供落盘预览（ToolResultStore）与 L0 压缩截断（StepCompactor）共用同一套头尾风格。
     *
     * @param text          原文；为 null 或长度未超过 maxChars 时原样返回（返回同一引用，便于调用方判断是否被截断）
     * @param maxChars      截断后保留正文（头 + 尾，不含中间标记）的最大字符数
     * @param markerBuilder 依据被省略的字符数生成中间标记
     * @return 截断后的文本，或原文
     */
    public static String truncateHeadTail(String text, int maxChars, IntFunction<String> markerBuilder) {
        if (text == null || maxChars <= 0 || text.length() <= maxChars) {
            return text;
        }
        int headLen = maxChars * 2 / 3;
        int tailLen = maxChars - headLen;
        int omitted = text.length() - headLen - tailLen;
        return text.substring(0, headLen)
                + markerBuilder.apply(omitted)
                + text.substring(text.length() - tailLen);
    }
}
