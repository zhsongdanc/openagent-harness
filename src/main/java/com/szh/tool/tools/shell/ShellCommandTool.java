package com.szh.tool.tools.shell;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.szh.tool.ShellTool;
import com.szh.tool.ToolContext;
import com.szh.tool.ToolDefinition;
import com.szh.utils.JsonUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;


/**
 * @author demussong
 * @describe shell 命令工具基类：把模型传入的 JSON 参数翻译成命令数组，在工作区目录下起子进程执行并回收输出。
 * <p>
 * 1. 命令通过参数数组交给 ProcessBuilder，不经过 shell 解释器，因此管道、重定向、glob 等语法不被支持，
 * 好处是参数不会被二次解释，拼接命令也不会引入注入风险；
 * 2. 统一在 {@link ToolContext#getWorkspace()} 下执行，子类只需关心相对路径；
 * 3. 有超时、退出码与输出截断处理，既避免 tail -f 这类常驻进程把整个 run 挂住，也避免大输出挤爆上下文。
 * <p>
 * 子类只需实现三件事：getCode 给出命令名、getToolDefinition 给出工具定义、buildCommand 把参数拼成命令数组。
 * @date 2026/9/2 21:37
 */
@Slf4j
public abstract class ShellCommandTool implements ShellTool {

    /**
     * 单条命令最长执行时间（秒）
     */
    private static final long TIMEOUT_SECONDS = 60;

    /**
     * 进程退出后等待输出读完的时间（秒）
     */
    private static final long DRAIN_SECONDS = 5;

    /**
     * 回传给模型的最大输出字符数，防止单条命令挤爆上下文窗口
     */
    private static final int MAX_OUTPUT_CHARS = 20000;

    @Override
    public String execute(ToolContext toolContext) {

        List<String> command;
        try {
            command = buildCommand(toolContext.getArgs());
        } catch (IllegalArgumentException e) {
            // 参数缺失属于模型传参错误，回传提示让下一轮自行纠正即可，不必打堆栈
            log.warn("build command failed, tool={}, args={}, reason={}", getCode(), toolContext.getArgs(), e.getMessage());
            return getCode() + " 参数错误：" + e.getMessage();
        } catch (Exception e) {
            log.error("build command failed, tool={}, args={}", getCode(), toolContext.getArgs(), e);
            return getCode() + " 参数解析失败：" + e.getMessage();
        }
        if (command == null || command.isEmpty()) {
            return getCode() + " 未能拼出可执行命令，请检查参数";
        }

        String cmdline = String.join(" ", command);
        log.info("shell exec: {}", cmdline);

        // 读输出放到独立线程：主线程 waitFor 超时后可直接销毁进程，不会卡在 readLine 上
        ExecutorService reader = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "shell-output-reader-" + getCode());
            thread.setDaemon(true);
            return thread;
        });
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
            File workspace = resolveWorkspace(toolContext);
            if (workspace != null) {
                builder.directory(workspace);
            }

            Process started = builder.start();
            process = started;
            Future<String> output = reader.submit(() -> readOutput(started));

            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return getCode() + " 执行超时（" + TIMEOUT_SECONDS + "s）已中断：" + cmdline;
            }

            String raw = output.get(DRAIN_SECONDS, TimeUnit.SECONDS);
            String result = truncate(raw);
            int exitCode = process.exitValue();
            log.info("shell done: cmd={}, exitCode={}, outputLength={}", cmdline, exitCode, raw.length());

            if (result.isBlank()) {
                // 退出码非 0 不一定是失败（grep 无匹配返回 1），只陈述事实不做定性
                return exitCode == 0
                        ? getCode() + " 执行完成，无输出"
                        : getCode() + " 执行完成，无输出，exitCode=" + exitCode;
            }
            // 退出码非 0 时不丢弃输出，只在尾部补上退出码交由模型判断
            return exitCode == 0 ? result : result + "\nexitCode=" + exitCode;

        } catch (Exception e) {
            log.error("execute command failed, cmd={}", cmdline, e);
            return "execute failed: " + e.getMessage();
        } finally {
            reader.shutdownNow();
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /**
     * 构建命令数组，第一个元素为可执行文件名；参数缺失时抛异常，由 execute 统一转成工具结果
     */
    protected abstract List<String> buildCommand(String args);

    protected String readOutput(Process process) throws Exception {

        StringBuilder sb = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        }

        return sb.toString();
    }

    /**
     * 输出超长时截断，并提示模型改用 head/tail/grep 缩小读取范围
     */
    private String truncate(String output) {
        if (output.length() <= MAX_OUTPUT_CHARS) {
            return output;
        }
        return output.substring(0, MAX_OUTPUT_CHARS)
                + "\n...[输出已截断，共 " + output.length() + " 字符，请用 head/tail/grep 分批读取]";
    }

    /**
     * 工作目录取工作区根目录，未配置或目录不存在时返回 null，表示沿用 JVM 当前目录
     */
    private File resolveWorkspace(ToolContext toolContext) {
        String workspace = toolContext.getWorkspace();
        if (workspace == null || workspace.isBlank()) {
            return null;
        }
        File dir = new File(workspace);
        return dir.isDirectory() ? dir : null;
    }

    /**
     * 快捷构建 shell 类工具定义，name/code 均取命令名，type 固定为 shell
     */
    protected static ToolDefinition definition(String code, String description, String parameters) {
        return ToolDefinition.builder()
                .name(code)
                .code(code)
                .type("shell")
                .description(description)
                .parameters(parameters)
                .build();
    }

    /**
     * 模型传入的 arguments 是 JSON 字符串；不是对象时退化为空对象，并把原始串当作 command 兜底，
     * 兼容模型直接回传裸命令（如 git 的 status -sb）的情况，子类不必各自兜异常
     */
    protected JsonNode parseArgs(String args) {
        String trimmed = args == null ? "" : args.trim();
        JsonNode node = trimmed.startsWith("{") ? JsonUtil.readTree(trimmed) : null;
        if (node != null && node.isObject()) {
            return node;
        }
        ObjectNode fallback = JsonUtil.getMapper().createObjectNode();
        if (!trimmed.isEmpty()) {
            fallback.put("command", trimmed);
        }
        return fallback;
    }

    /**
     * 取字符串参数，缺失或全空白返回 null
     */
    protected String text(JsonNode args, String field) {
        JsonNode value = args.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText().trim();
        return text.isEmpty() ? null : text;
    }

    /**
     * 取必填字符串参数，缺失直接抛异常
     */
    protected String requireText(JsonNode args, String field) {
        String value = text(args, field);
        if (value == null) {
            throw new IllegalArgumentException("缺少必填参数 " + field);
        }
        return value;
    }

    /**
     * 取整数参数，缺失或无法解析时返回默认值；模型常把数字写成字符串，两种都接受
     */
    protected int integer(JsonNode args, String field, int defaultValue) {
        JsonNode value = args.get(field);
        if (value == null || value.isNull()) {
            return defaultValue;
        }
        if (value.isNumber()) {
            return value.asInt(defaultValue);
        }
        try {
            return Integer.parseInt(value.asText().trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * 追加选项：按空白拆分后逐个加入，避免 "-l -a" 被当成一个参数；模型未传选项时用默认选项
     */
    protected void appendOptions(List<String> command, String options, String defaultOptions) {
        appendArgs(command, options != null ? options : defaultOptions);
    }

    /**
     * 按空白拆分追加多个参数，null 或空白忽略
     */
    protected void appendArgs(List<String> command, String args) {
        if (args == null || args.isBlank()) {
            return;
        }
        for (String arg : args.trim().split("\\s+")) {
            command.add(arg);
        }
    }

    /**
     * 追加单个参数，null 忽略
     */
    protected void appendArg(List<String> command, String arg) {
        if (arg != null) {
            command.add(arg);
        }
    }
}
