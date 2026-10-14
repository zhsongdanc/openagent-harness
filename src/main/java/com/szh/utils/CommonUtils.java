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
     * 从轮次标识（形如 {@code turn_7}）解析出可比较的轮次序号。
     * <p>
     * 轮次名由运行时的 {@code getTurnName(int)} 生成，事件与文件快照都按它归档；
     * 回溯（resume 截止 / 事件截断 / 快照还原）需要按序号做数值比较，故抽取为公共解析。
     * 取末尾连续数字，null/无数字/解析失败一律返回 0（视为最早、不会被任何“>= N”过滤命中）。
     */
    public static int parseTurnSeq(String turnId) {
        if (turnId == null || turnId.isBlank()) {
            return 0;
        }
        int end = turnId.length();
        int start = end;
        while (start > 0 && Character.isDigit(turnId.charAt(start - 1))) {
            start--;
        }
        if (start == end) {
            return 0;
        }
        try {
            return Integer.parseInt(turnId.substring(start, end));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 展开字符串中的 {@code ~}（用户 home）与 {@code ${VAR}}（环境变量）。
     * <p>
     * 抽为公共方法，供 {@code McpConfigLoader} 与验证闭环 {@code VerifyConfigLoader} 共用同一套展开语义，
     * 使共享配置文件不必硬编码绝对路径或 secret。null 原样返回；未定义的环境变量替换为空串。
     */
    public static String expand(String s) {
        if (s == null) {
            return null;
        }
        String out = s;
        if (out.startsWith("~/")) {
            out = System.getProperty("user.home") + out.substring(1);
        } else if (out.equals("~")) {
            out = System.getProperty("user.home");
        }
        // ${VAR} 替换：让共享配置不必硬编码 secret
        if (out.contains("${")) {
            StringBuilder sb = new StringBuilder();
            int i = 0;
            while (i < out.length()) {
                int start = out.indexOf("${", i);
                if (start < 0) {
                    sb.append(out, i, out.length());
                    break;
                }
                int end = out.indexOf('}', start + 2);
                if (end < 0) {
                    sb.append(out, i, out.length());
                    break;
                }
                sb.append(out, i, start);
                String varName = out.substring(start + 2, end);
                String varVal = System.getenv(varName);
                sb.append(varVal == null ? "" : varVal);
                i = end + 1;
            }
            out = sb.toString();
        }
        return out;
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
