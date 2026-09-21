package com.szh.trace.replay;

import com.szh.context.dto.AssistantMessageItem;
import com.szh.context.dto.ReasoningMessageItem;
import com.szh.context.dto.ToolMessageItem;
import com.szh.context.dto.UserMessageItem;
import com.szh.event.CallToolFinishedEvent;
import com.szh.event.CallToolStartedEvent;
import com.szh.event.Event;
import com.szh.event.EventEnum;
import com.szh.event.ModelResponseEvent;
import com.szh.event.ReasoningEvent;
import com.szh.event.RunCompletedEvent;
import com.szh.event.RunStartedEvent;
import com.szh.event.UserMessageEvent;
import com.szh.model.dto.ToolCall;
import com.szh.store.EventStoreFactory;
import com.szh.utils.ConfigUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author demussong
 * @describe Trace 回放器：把 {@link com.szh.store.EventStore} 里某 session 的事件流重新渲染成
 * 可读的执行时间线（run → turn → round → 各事件），解决“调试困难”——不必再翻原始日志或裸 JSONL。
 * <p>
 * 事件日志是唯一真相源，回放只读不改：
 * <ul>
 *   <li>{@link #render(String)}：控制台友好的纯文本时间线；</li>
 *   <li>{@link #exportHtml(String)}：导出自包含 HTML（按事件类型着色、长内容可折叠），落到
 *       {@code {workspace}/.agent-data/traces/{sessionId}.html}，用浏览器即可可视化查看。</li>
 * </ul>
 * 说明：token 用量只在运行期 TokenTracker 统计、未随事件落库，故回放呈现的是流程与耗时，不含逐轮 token。
 * @date 2026/9/21
 */
@Slf4j
public final class TraceReplay {

    /**
     * 控制台单条内容截断阈值，避免超长工具输出刷屏（HTML 导出不截断，放折叠块里）
     */
    private static final int CONSOLE_MAX_CHARS = 800;

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    private TraceReplay() {
    }

    /**
     * 加载并按时间排序某 session 的全部事件
     */
    private static List<Event> loadEvents(String sessionId) {
        List<Event> events = new ArrayList<>(EventStoreFactory.createEventStore().getEvents(sessionId));
        events.sort(Comparator.comparingLong(Event::getTimestamp));
        return events;
    }

    // ==================== 文本时间线 ====================

    /**
     * 渲染纯文本执行时间线，供控制台/REPL 直接打印
     */
    public static String render(String sessionId) {
        List<Event> events = loadEvents(sessionId);
        StringBuilder sb = new StringBuilder();
        sb.append("====== Trace Replay | session=").append(sessionId)
                .append(" | events=").append(events.size()).append(" ======\n");
        if (events.isEmpty()) {
            sb.append("(无事件记录：session 不存在，或存储引擎为 MEMORY 且非当前进程写入)\n");
            return sb.toString();
        }

        // 记录每个 run 的起始时间，RUN_COMPLETED 时算耗时；round 变化时打印轮次分隔
        Map<String, Long> runStart = new HashMap<>();
        String currentRun = null;
        int currentRound = -1;
        for (Event e : events) {
            if (!equals(currentRun, e.getRunId())) {
                currentRun = e.getRunId();
                currentRound = -1;
                sb.append("\n── Run ").append(shortId(e.getRunId()))
                        .append(" | turn=").append(e.getTurnId()).append(" ──\n");
            }
            if (e.getType() == EventEnum.RUN_STARTED) {
                runStart.put(e.getRunId(), e.getTimestamp());
            }
            if (e.getRound() > 0 && e.getRound() != currentRound) {
                currentRound = e.getRound();
                sb.append("  Round ").append(currentRound).append("\n");
            }
            sb.append("    ").append(TIME_FMT.format(Instant.ofEpochMilli(e.getTimestamp())))
                    .append("  ").append(describe(e, runStart)).append("\n");
        }
        sb.append("\n====== End Replay ======");
        return sb.toString();
    }

    /**
     * 单事件的文本描述，按类型分派
     */
    private static String describe(Event e, Map<String, Long> runStart) {
        return switch (e.getType()) {
            case RUN_STARTED -> "▶ RUN_STARTED";
            case USER_INPUT -> "[USER] " + clip(userContent(e));
            case MODEL_REASONING -> "[THINK] " + clip(reasoningContent(e));
            case CALL_MODEL_FINISHED -> "[MODEL] " + clip(modelContent(e));
            case CALL_TOOL_STARTED -> {
                CallToolStartedEvent s = (CallToolStartedEvent) e;
                yield "[TOOL ▶] " + s.getToolName() + "  args=" + clip(nvl(s.getParameters()));
            }
            case CALL_TOOL_FINISHED -> {
                CallToolFinishedEvent f = (CallToolFinishedEvent) e;
                yield "[TOOL ✓] " + f.getToolName() + "  → " + clip(nvl(f.getOutput()));
            }
            case RUN_COMPLETED -> {
                RunCompletedEvent c = (RunCompletedEvent) e;
                Long start = runStart.get(e.getRunId());
                String elapsed = start == null ? "" : "  (elapsed " + (e.getTimestamp() - start) + "ms)";
                yield "■ DONE" + elapsed + ": " + clip(nvl(c.getResult()));
            }
        };
    }

    private static String userContent(Event e) {
        if (e instanceof UserMessageEvent u && u.getMessageItem() instanceof UserMessageItem item) {
            return nvl(item.getContent());
        }
        return "";
    }

    private static String reasoningContent(Event e) {
        if (e instanceof ReasoningEvent r && r.getMessageItem() instanceof ReasoningMessageItem item) {
            return nvl(item.getContent());
        }
        return "";
    }

    private static String modelContent(Event e) {
        if (e instanceof ModelResponseEvent m && m.getMessageItem() instanceof AssistantMessageItem item) {
            if (item.isCallTool()) {
                StringBuilder sb = new StringBuilder();
                for (ToolCall call : item.effectiveToolCalls()) {
                    if (sb.length() > 0) {
                        sb.append(" | ");
                    }
                    sb.append("tool_call→").append(call.toolCode()).append("(").append(nvl(call.toolArgs())).append(")");
                }
                return sb.toString();
            }
            return nvl(item.getContent());
        }
        return "";
    }

    // ==================== HTML 可视化 ====================

    /**
     * 导出自包含 HTML 时间线，返回写出的文件路径；失败返回 null（不抛，回放属诊断能力，不该中断主流程）
     */
    public static Path exportHtml(String sessionId) {
        List<Event> events = loadEvents(sessionId);
        // 导出目录：优先 trace.html.dir，缺省落在工作区 .agent-data/traces 下
        String configuredDir = ConfigUtil.get("trace.html.dir");
        Path dir = (configuredDir == null || configuredDir.isBlank())
                ? Paths.get(ConfigUtil.get("project.workspace", System.getProperty("user.dir")), ".agent-data", "traces")
                : Paths.get(configuredDir);
        Path file = dir.resolve(sanitize(sessionId) + ".html");
        try {
            Files.createDirectories(dir);
            Files.writeString(file, buildHtml(sessionId, events), StandardCharsets.UTF_8);
            log.info("Trace HTML exported: {}", file);
            return file;
        } catch (IOException | RuntimeException ex) {
            log.error("export trace html failed, session={}, file={}", sessionId, file, ex);
            return null;
        }
    }

    private static String buildHtml(String sessionId, List<Event> events) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang=\"zh\"><head><meta charset=\"utf-8\">")
                .append("<title>Trace ").append(esc(sessionId)).append("</title>")
                .append("<style>")
                .append("body{font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;margin:0;background:#0f1115;color:#e6e6e6;}")
                .append("header{padding:16px 24px;background:#171a21;border-bottom:1px solid #262b36;position:sticky;top:0;}")
                .append("header h1{font-size:16px;margin:0;}header .meta{color:#8b93a7;font-size:12px;margin-top:4px;}")
                .append(".wrap{padding:16px 24px;}")
                .append(".run{border:1px solid #262b36;border-radius:8px;margin-bottom:16px;overflow:hidden;}")
                .append(".run>.run-head{background:#1b2029;padding:8px 14px;font-weight:600;font-size:13px;color:#c8d0e0;}")
                .append(".round{padding:4px 14px;color:#7f8aa3;font-size:12px;border-top:1px dashed #262b36;margin-top:4px;}")
                .append(".ev{display:flex;gap:10px;padding:6px 14px;border-top:1px solid #1e232d;font-size:13px;}")
                .append(".ev .t{color:#6b7488;flex:0 0 92px;font-variant-numeric:tabular-nums;}")
                .append(".ev .tag{flex:0 0 92px;font-weight:600;}")
                .append(".ev .body{flex:1;white-space:pre-wrap;word-break:break-word;}")
                .append(".USER .tag{color:#4ea1ff;}.THINK .tag{color:#b58cff;}.MODEL .tag{color:#41d19a;}")
                .append(".TOOLSTART .tag{color:#ffb454;}.TOOLDONE .tag{color:#8ee06a;}")
                .append(".DONE .tag{color:#ff6b81;}.RUNSTART .tag{color:#8b93a7;}")
                .append("details>summary{cursor:pointer;color:#8b93a7;}")
                .append("</style></head><body>");
        sb.append("<header><h1>Trace Replay</h1><div class=\"meta\">session=")
                .append(esc(sessionId)).append(" · events=").append(events.size())
                .append(" · generated=").append(esc(TIME_FMT.format(Instant.now()))).append("</div></header>");
        sb.append("<div class=\"wrap\">");

        Map<String, Long> runStart = new HashMap<>();
        boolean runOpen = false;
        String currentRun = null;
        int currentRound = -1;
        for (Event e : events) {
            if (!equals(currentRun, e.getRunId())) {
                if (runOpen) {
                    sb.append("</div>");
                }
                currentRun = e.getRunId();
                currentRound = -1;
                runOpen = true;
                sb.append("<div class=\"run\"><div class=\"run-head\">Run ")
                        .append(esc(shortId(e.getRunId()))).append(" · turn=").append(esc(e.getTurnId()))
                        .append("</div>");
            }
            if (e.getType() == EventEnum.RUN_STARTED) {
                runStart.put(e.getRunId(), e.getTimestamp());
            }
            if (e.getRound() > 0 && e.getRound() != currentRound) {
                currentRound = e.getRound();
                sb.append("<div class=\"round\">Round ").append(currentRound).append("</div>");
            }
            appendHtmlEvent(sb, e, runStart);
        }
        if (runOpen) {
            sb.append("</div>");
        }
        if (events.isEmpty()) {
            sb.append("<div class=\"ev\"><div class=\"body\">无事件记录</div></div>");
        }
        sb.append("</div></body></html>");
        return sb.toString();
    }

    private static void appendHtmlEvent(StringBuilder sb, Event e, Map<String, Long> runStart) {
        String cls;
        String tag;
        String body;
        switch (e.getType()) {
            case RUN_STARTED -> {
                cls = "RUNSTART";
                tag = "RUN_STARTED";
                body = "";
            }
            case USER_INPUT -> {
                cls = "USER";
                tag = "USER";
                body = userContent(e);
            }
            case MODEL_REASONING -> {
                cls = "THINK";
                tag = "REASONING";
                body = reasoningContent(e);
            }
            case CALL_MODEL_FINISHED -> {
                cls = "MODEL";
                tag = "MODEL";
                body = modelContent(e);
            }
            case CALL_TOOL_STARTED -> {
                CallToolStartedEvent s = (CallToolStartedEvent) e;
                cls = "TOOLSTART";
                tag = "TOOL ▶";
                body = s.getToolName() + "  args=" + nvl(s.getParameters());
            }
            case CALL_TOOL_FINISHED -> {
                CallToolFinishedEvent f = (CallToolFinishedEvent) e;
                cls = "TOOLDONE";
                tag = "TOOL ✓";
                body = f.getToolName() + "  → " + nvl(f.getOutput());
            }
            case RUN_COMPLETED -> {
                RunCompletedEvent c = (RunCompletedEvent) e;
                Long start = runStart.get(e.getRunId());
                cls = "DONE";
                tag = "DONE";
                body = (start == null ? "" : "(elapsed " + (e.getTimestamp() - start) + "ms) ") + nvl(c.getResult());
            }
            default -> {
                cls = "RUNSTART";
                tag = e.getType().name();
                body = "";
            }
        }
        sb.append("<div class=\"ev ").append(cls).append("\">")
                .append("<div class=\"t\">").append(esc(TIME_FMT.format(Instant.ofEpochMilli(e.getTimestamp())))).append("</div>")
                .append("<div class=\"tag\">").append(esc(tag)).append("</div>")
                .append("<div class=\"body\">");
        // 长内容折叠，短内容直接展示，兼顾概览与细节
        if (body.length() > 300) {
            sb.append("<details><summary>展开(").append(body.length()).append(" 字符)</summary>")
                    .append(esc(body)).append("</details>");
        } else {
            sb.append(esc(body));
        }
        sb.append("</div></div>");
    }

    // ==================== 命令行入口 ====================

    /**
     * 独立回放入口：{@code java ... TraceReplay <sessionId> [--html]}。
     * --html 时额外导出可视化文件并打印路径。
     */
    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("usage: TraceReplay <sessionId> [--html]");
            return;
        }
        String sessionId = args[0];
        System.out.println(render(sessionId));
        for (String arg : args) {
            if ("--html".equals(arg)) {
                Path file = exportHtml(sessionId);
                System.out.println(file == null ? "HTML 导出失败，详见日志" : "HTML 已导出: " + file);
            }
        }
    }

    // ==================== 工具方法 ====================

    private static String clip(String text) {
        String oneLine = nvl(text).replace("\r", " ").replace("\n", " ⏎ ");
        if (oneLine.length() <= CONSOLE_MAX_CHARS) {
            return oneLine;
        }
        return oneLine.substring(0, CONSOLE_MAX_CHARS) + " …(+" + (oneLine.length() - CONSOLE_MAX_CHARS) + " 字符)";
    }

    private static String esc(String text) {
        return nvl(text).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String shortId(String id) {
        return id == null ? "null" : (id.length() > 12 ? id.substring(0, 12) : id);
    }

    private static String sanitize(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return "default";
        }
        String cleaned = sessionId.trim().replaceAll("[^a-zA-Z0-9_-]", "_");
        return cleaned.isEmpty() ? "default" : cleaned;
    }

    private static boolean equals(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }
}
