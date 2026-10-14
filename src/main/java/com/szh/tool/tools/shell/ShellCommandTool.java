package com.szh.tool.tools.shell;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.szh.tool.ShellTool;
import com.szh.tool.ToolContext;
import com.szh.tool.ToolDefinition;
import com.szh.tool.security.PermissionDecision;
import com.szh.tool.security.ShellSecurity;
import com.szh.utils.JsonUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.List;
import java.util.Map;


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

        // 安全闸门：先做权限决策（危险/越界命令可能被拒绝或要求人工确认），再按档位包装进 OS 级沙箱
        PermissionDecision decision = ShellSecurity.authorize(command, toolContext.getWorkspace());
        if (!decision.isAllowed()) {
            log.warn("command blocked by security policy: {} ({})", cmdline, decision.getReason());
            return getCode() + " 被安全策略拦截：" + decision.getReason();
        }
        List<String> execCommand = ShellSecurity.wrap(command, toolContext.getWorkspace());

        // 子进程执行（起进程/独立线程读输出/超时销毁）统一交给 ShellRunner，本类只负责安全闸门与结果格式化
        ShellRunner.ShellResult result = ShellRunner.run(execCommand, resolveWorkspace(toolContext),
                environment(), TIMEOUT_SECONDS);
        if (result.error() != null) {
            return translateExecError(result.error());
        }
        if (result.timedOut()) {
            return getCode() + " 执行超时（" + TIMEOUT_SECONDS + "s）已中断：" + cmdline;
        }

        String raw = result.output();
        int exitCode = result.exitCode();
        log.info("shell done: cmd={}, exitCode={}, outputLength={}", cmdline, exitCode, raw.length());

        // 完整输出存入文件，不再截断；模型通过 read_tool_result 分页查阅
        if (raw.isBlank()) {
            return exitCode == 0
                    ? getCode() + " 执行完成，无输出"
                    : getCode() + " 执行完成，无输出，exitCode=" + exitCode;
        }
        return exitCode == 0 ? raw : raw + "\nexitCode=" + exitCode;
    }

    /**
     * 构建命令数组，第一个元素为可执行文件名；参数缺失时抛异常，由 execute 统一转成工具结果
     */
    protected abstract List<String> buildCommand(String args);

    /**
     * 将子进程启动异常转为面向模型的友好回执：识别「命令未安装」（ENOENT），
     * 把 {@code Cannot run program "mvn" ... error: 2 (No such file or directory)} 这类 Java 视角堆栈信息
     * 翻译成“不要重试该命令、改用环境已有手段”的明确指令，让模型一轮止损。
     * 非 ENOENT 的其它异常仍原样返回 {@code execute failed: ...}。
     * <p>
     * 与 {@link com.szh.tool.BinaryProbe} 按需注册互补：正常环境下工具压根不发 schema，
     * 探测关闭或 PATH 异常时由这条兜底。
     */
    public static String translateExecError(String error) {
        if (error != null
                && error.contains("Cannot run program")
                && (error.contains("No such file or directory") || error.contains("error: 2") || error.contains("error=2"))) {
            String name = extractProgramName(error);
            String cmd = (name == null || name.isBlank()) ? "该命令" : name;
            return "命令未安装：" + cmd + "（PATH 中找不到）。请勿再尝试 " + cmd
                    + " 相关命令，改用当前环境已具备的工具/语言（如直接写 Python/用 shell 内建）。";
        }
        return "execute failed: " + error;
    }

    /**
     * 从 {@code Cannot run program "<bin>" ...} 消息里取出第一个引号包裹的可执行文件名，取不到返回 null
     */
    private static String extractProgramName(String error) {
        String marker = "Cannot run program \"";
        int start = error.indexOf(marker);
        if (start < 0) {
            return null;
        }
        int from = start + marker.length();
        int to = error.indexOf('"', from);
        return to > from ? error.substring(from, to) : null;
    }

    /**
     * 子进程额外环境变量钩子：默认空，不影响既有 shell 工具。
     * 需要关掉交互式提示/编辑器的工具（如 git commit / git push）覆盖此方法注入 GIT_EDITOR、
     * GIT_TERMINAL_PROMPT 等，避免子进程等待 stdin 而触发本类的执行超时。
     */
    protected Map<String, String> environment() {
        return Map.of();
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
