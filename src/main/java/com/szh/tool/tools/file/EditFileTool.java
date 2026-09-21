package com.szh.tool.tools.file;

import com.fasterxml.jackson.databind.JsonNode;
import com.szh.tool.ToolContext;
import com.szh.tool.ToolDefinition;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 精确替换编辑工具（对齐主流 harness 的 Edit 工具范式）：在已有文件中把 old_text
 * 精确替换为 new_text，结果回传统一 diff，让模型确认改动没有跑偏。
 * <p>
 * 安全语义：
 * <ol>
 *   <li>old_text 必须在文件中精确出现且默认唯一——出现多次说明定位不充分，拒绝执行，
 *       逼模型补充上下文；确需全量替换时显式传 replace_all=true；</li>
 *   <li>找不到 old_text 直接失败，绝不落盘——防止模型基于过期文件内容盲改；</li>
 *   <li>old_text 与 new_text 相同视为无操作，避免空转刷轮次。</li>
 * </ol>
 *
 * @author demussong
 * @date 2026/9/21
 */
@Slf4j
public class EditFileTool extends FileToolSupport {

    public static final String CODE = "edit_file";

    /**
     * diff 输出上限（行）：超大改动截断，完整内容可让模型重新 read_file
     */
    private static final int MAX_DIFF_LINES = 200;

    /**
     * diff 上下文行数
     */
    private static final int DIFF_CONTEXT = 3;

    private static final ToolDefinition TOOL_DEFINITION = ToolDefinition.builder()
            .name(CODE)
            .code(CODE)
            .type("file")
            .description("对已有文件做精确字符串替换编辑：把 old_text 替换为 new_text。"
                    + "old_text 必须在文件中精确唯一出现（含缩进与空白），否则拒绝执行；"
                    + "多处相同片段需全部替换时传 replace_all=true。新建文件请用 write_file")
            .parameters("{\"type\":\"object\",\"properties\":{"
                    + "\"path\":{\"type\":\"string\",\"description\":\"文件路径，相对路径以工作区为基准\"},"
                    + "\"old_text\":{\"type\":\"string\",\"description\":\"要被替换的原文片段，必须与文件内容逐字符一致\"},"
                    + "\"new_text\":{\"type\":\"string\",\"description\":\"替换后的新文本\"},"
                    + "\"replace_all\":{\"type\":\"boolean\",\"description\":\"是否替换所有出现，默认 false（要求唯一）\"}},"
                    + "\"required\":[\"path\",\"old_text\",\"new_text\"]}")
            .build();

    @Override
    public String getCode() {
        return CODE;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return TOOL_DEFINITION;
    }

    @Override
    public String execute(ToolContext toolContext) {
        try {
            JsonNode args = parseArgs(toolContext.getArgs());
            Path file = resolvePath(text(args, "path"), toolContext);
            checkReadable(file, toolContext);
            checkWritable(file, toolContext);

            String oldText = rawText(args, "old_text");
            String newText = rawText(args, "new_text");
            if (oldText == null || oldText.isEmpty()) {
                throw new IllegalArgumentException("old_text 不能为空（新建文件请用 write_file）");
            }
            if (newText == null) {
                throw new IllegalArgumentException("缺少必填参数 new_text（删除片段请传空字符串）");
            }
            if (oldText.equals(newText)) {
                return CODE + " 无操作：old_text 与 new_text 相同，文件未改动";
            }
            boolean replaceAll = booleanArg(args, "replace_all", false);

            String content = Files.readString(file, StandardCharsets.UTF_8);
            int occurrences = countOccurrences(content, oldText);
            if (occurrences == 0) {
                throw new IllegalArgumentException("old_text 在文件中未找到，请先 read_file 确认最新内容"
                        + "（注意缩进、空白与换行必须逐字符一致）");
            }
            if (occurrences > 1 && !replaceAll) {
                throw new IllegalArgumentException("old_text 在文件中出现 " + occurrences + " 次，无法唯一定位；"
                        + "请补充上下文使其唯一，或传 replace_all=true 全部替换");
            }

            String updated = replaceAll
                    ? content.replace(oldText, newText)
                    : replaceFirst(content, oldText, newText);
            writeFile(file, updated);

            String diff = buildUnifiedDiff(content, updated);
            log.info("edit_file: {}, occurrences={}, replaced={}", file, occurrences, replaceAll ? occurrences : 1);
            return "已编辑 " + file + "（替换 " + (replaceAll ? occurrences : 1) + " 处）\n" + diff;
        } catch (IllegalArgumentException e) {
            log.warn("edit_file 参数错误, args={}, reason={}", toolContext.getArgs(), e.getMessage());
            return CODE + " 参数错误：" + e.getMessage();
        } catch (Exception e) {
            log.error("edit_file 执行失败, args={}", toolContext.getArgs(), e);
            return CODE + " 执行失败：" + e.getMessage();
        }
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

    /**
     * 只替换第一处：用索引拼接而非 String.replaceFirst，避免 old_text 里的
     * 正则元字符与 new_text 里的 $ 分组引用被二次解释
     */
    private static String replaceFirst(String content, String oldText, String newText) {
        int idx = content.indexOf(oldText);
        return content.substring(0, idx) + newText + content.substring(idx + oldText.length());
    }

    /**
     * 生成带上下文的统一 diff（行级 LCS），让模型在结果里直接看到实际改动。
     * 文件行数在 LCS 的 O(n*m) DP 承受范围内才计算，超大文件退化为只报统计。
     */
    private String buildUnifiedDiff(String oldContent, String newContent) {
        String[] oldLines = oldContent.split("\n", -1);
        String[] newLines = newContent.split("\n", -1);
        if ((long) oldLines.length * newLines.length > 4_000_000L) {
            return "（文件过大，跳过 diff 生成：" + oldLines.length + " 行 -> " + newLines.length + " 行）";
        }

        List<String> diffLines = lcsDiff(oldLines, newLines);
        List<String> hunks = extractHunks(diffLines, DIFF_CONTEXT);
        if (hunks.isEmpty()) {
            return "（无行级差异，可能是行内改动被规整）";
        }
        if (hunks.size() > MAX_DIFF_LINES) {
            List<String> capped = new ArrayList<>(hunks.subList(0, MAX_DIFF_LINES));
            capped.add("... [diff 已截断，共 " + hunks.size() + " 行，可用 read_file 查看完整内容]");
            hunks = capped;
        }
        return String.join("\n", hunks);
    }

    /**
     * 行级 LCS diff：DP 求最长公共子序列后回溯，产出 " "/"-"/"+" 前缀的行序列
     */
    private static List<String> lcsDiff(String[] a, String[] b) {
        int n = a.length;
        int m = b.length;
        int[][] dp = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                dp[i][j] = a[i].equals(b[j])
                        ? dp[i + 1][j + 1] + 1
                        : Math.max(dp[i + 1][j], dp[i][j + 1]);
            }
        }
        List<String> result = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < n && j < m) {
            if (a[i].equals(b[j])) {
                result.add("  " + a[i]);
                i++;
                j++;
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                result.add("- " + a[i]);
                i++;
            } else {
                result.add("+ " + b[j]);
                j++;
            }
        }
        while (i < n) {
            result.add("- " + a[i++]);
        }
        while (j < m) {
            result.add("+ " + b[j++]);
        }
        return result;
    }

    /**
     * 从完整 diff 行序列中抽取变更块：每个 +/- 行段附带前后 context 行，块间用 @@ 分隔
     */
    private static List<String> extractHunks(List<String> diffLines, int context) {
        List<String> hunks = new ArrayList<>();
        int n = diffLines.size();
        int i = 0;
        while (i < n) {
            String line = diffLines.get(i);
            if (!isChange(line)) {
                i++;
                continue;
            }
            int start = Math.max(0, i - context);
            int end = i;
            // 向后扩展：把相邻（间隔 <= 2*context）的变更并进同一块
            while (end < n) {
                if (isChange(diffLines.get(end))) {
                    end++;
                } else {
                    int lookahead = 0;
                    while (end + lookahead < n && !isChange(diffLines.get(end + lookahead))) {
                        lookahead++;
                    }
                    if (end + lookahead < n && lookahead <= 2 * context) {
                        end += lookahead;
                    } else {
                        break;
                    }
                }
            }
            int stop = Math.min(n, end + context);
            hunks.add("@@ 变更块 @@");
            for (int k = start; k < stop; k++) {
                hunks.add(diffLines.get(k));
            }
            i = stop;
        }
        return hunks;
    }

    private static boolean isChange(String diffLine) {
        return diffLine.startsWith("- ") || diffLine.startsWith("+ ");
    }
}
