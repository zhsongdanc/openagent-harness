package com.szh.tool.tools.file;

import com.fasterxml.jackson.databind.JsonNode;
import com.szh.tool.ToolContext;
import com.szh.tool.ToolDefinition;
import com.szh.tool.security.SandboxPolicy;
import com.szh.tool.tools.meta.ConsoleQuestionPrompter;
import com.szh.tool.tools.meta.Question;
import com.szh.utils.ConfigUtil;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 删除文件工具（对标主流 harness 的 delete/remove file 能力）。删除是不可逆的破坏性操作，
 * 故在<b>工具层</b>加了三道硬限制，不完全依赖 shell 权限闸门（模型可能直接 rm，也可能绕过沙箱档位）：
 * <ol>
 *   <li><b>路径必须落在 repo root 之内</b>：以 {@link SandboxPolicy#getWorkspaceRoot()} 为边界做包含校验，
 *       且<b>不</b>受 {@code file.tools.allowOutside} 放开——删除比读写更危险，工作区外一律拒绝；</li>
 *   <li><b>禁止目录删除</b>：只删单个文件，命中目录直接拒绝（递归删除副作用面太大，交人工处理）；</li>
 *   <li><b>敏感删除人工确认</b>：删除大文件 / 特定后缀（如 {@code .java}、{@code pom.xml}）前，
 *       复用 {@code ask_user_question} 同一套 {@link ConsoleQuestionPrompter} 交互征求确认；
 *       临时文件 / 测试文件命中豁免规则时静默放行，不打断模型。非交互环境按
 *       {@code file.delete.confirm.autoApprove} 兜底（默认拒绝，安全优先）。</li>
 * </ol>
 * 删除前会 {@link #captureSnapshot} 拍 before-image，使 {@code /rewind} 能把误删的文件还原回来
 * （与 write_file/edit_file 同一套快照机制）。
 *
 * @author demussong
 * @date 2026/10/14
 */
@Slf4j
public class DeleteFileTool extends FileToolSupport {

    public static final String CODE = "delete_file";

    /** 确认问询里「同意删除」选项的 label，用于解析用户应答 */
    private static final String CONFIRM_LABEL = "确认删除";

    private static final ToolDefinition TOOL_DEFINITION = ToolDefinition.builder()
            .name(CODE)
            .code(CODE)
            .type("file")
            .description("删除单个工作区文件（不可逆）。硬限制：路径必须在 repo root 之内、禁止删除目录。"
                    + "删除大文件或源码/构建描述文件（如 .java、pom.xml）会先向用户征求确认，临时/测试文件自动放行。"
                    + "path 支持工作区相对路径或绝对路径")
            .parameters("{\"type\":\"object\",\"properties\":{"
                    + "\"path\":{\"type\":\"string\",\"description\":\"要删除的文件路径，相对路径以工作区为基准\"}},"
                    + "\"required\":[\"path\"]}")
            .build();

    private final ConsoleQuestionPrompter prompter;

    public DeleteFileTool() {
        this(new ConsoleQuestionPrompter());
    }

    /**
     * 测试构造：注入自定义问询器（可模拟用户应答/非交互），脱离真实 stdin 验证确认链路
     */
    public DeleteFileTool(ConsoleQuestionPrompter prompter) {
        this.prompter = prompter;
    }

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
            Path root = checkWithinRepo(file, toolContext);

            if (!Files.exists(file)) {
                throw new IllegalArgumentException("文件不存在: " + file);
            }
            // 硬限制：禁止目录删除（递归删除副作用面大，交人工处理）
            if (Files.isDirectory(file)) {
                throw new IllegalArgumentException("禁止删除目录: " + file
                        + "（delete_file 只删单个文件，目录请人工确认后用 shell 处理）");
            }

            long size = Files.size(file);
            String rel = relativize(root, file);
            if (needsConfirm(file, size)) {
                if (!confirmDelete(rel, size)) {
                    log.info("delete_file cancelled by user/autoApprove: {}", file);
                    return CODE + " 已取消：未获得删除确认，文件保留 " + rel;
                }
            }

            // 删除与 edit/write 共用同路径写锁，避免删掉并发编辑中途的半态文件；
            // 人工确认等待不进锁（可能长时间阻塞 stdin），锁内只做存在复查 + 快照 + 删除
            return withPathLock(file, () -> {
                if (!Files.exists(file)) {
                    throw new IllegalArgumentException("文件已不存在（可能被同轮并发操作删除）: " + file);
                }
                // 删除前拍 before-image，供 /rewind 回溯还原（本轮首次改动才记，幂等）
                captureSnapshot(file, toolContext);
                Files.delete(file);
                log.info("delete_file: {} ({} bytes)", file, size);
                return "已删除 " + rel + "（" + humanSize(size) + "）";
            });
        } catch (IllegalArgumentException e) {
            log.warn("delete_file 参数错误, args={}, reason={}", toolContext.getArgs(), e.getMessage());
            return CODE + " 参数错误：" + e.getMessage();
        } catch (Exception e) {
            log.error("delete_file 执行失败, args={}", toolContext.getArgs(), e);
            return CODE + " 执行失败：" + e.getMessage();
        }
    }

    /**
     * 硬限制：删除路径必须落在 repo root（工作区根）之内，且不放开到工作区外。
     * 复用 {@link SandboxPolicy} 的真实路径口径（已 normalize + 解析符号链接），避免 /tmp vs /private/tmp 误判。
     *
     * @return repo root，供后续计算相对展示路径
     */
    private Path checkWithinRepo(Path resolved, ToolContext toolContext) {
        Path root = SandboxPolicy.fromConfig(toolContext.getWorkspace()).getWorkspaceRoot();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("路径 " + resolved + " 在 repo root（" + root
                    + ")之外，禁止删除（删除操作一律限制在工作区内，不受 file.tools.allowOutside 放开）");
        }
        return root;
    }

    /**
     * 是否需要人工确认：确认总开关关闭 → 不确认；命中豁免规则（临时/测试文件）→ 不确认；
     * 否则文件字节数达阈值，或文件名命中敏感后缀/文件名清单 → 需确认。
     */
    private boolean needsConfirm(Path file, long size) {
        if (!ConfigUtil.getBoolean("file.delete.confirm.enabled", true)) {
            return false;
        }
        Pattern exempt = exemptPattern();
        if (exempt != null && exempt.matcher(file.toString()).find()) {
            return false;
        }
        long minBytes = longConfig("file.delete.confirm.minBytes", 10240L);
        if (minBytes > 0 && size >= minBytes) {
            return true;
        }
        String fileName = file.getFileName() == null ? "" : file.getFileName().toString();
        for (String token : splitCsv(ConfigUtil.get("file.delete.confirm.suffixes", ".java,pom.xml"))) {
            if (token.startsWith(".")) {
                if (fileName.endsWith(token)) {
                    return true;
                }
            } else if (fileName.equalsIgnoreCase(token)) {
                // 无 . 前缀视为完整文件名匹配（如 pom.xml、Dockerfile）
                return true;
            }
        }
        return false;
    }

    /**
     * 豁免规则（正则，命中即无需确认）：默认覆盖构建产物/临时目录、临时后缀、以及测试文件命名，
     * 让「删测试文件、删 target 里的产物」这类低风险操作不打断模型。
     */
    private Pattern exemptPattern() {
        String regex = ConfigUtil.get("file.delete.confirm.exempt.regex",
                "(?:^|/)(?:test|tests|tmp|temp|target|build|out|node_modules|\\.agent-data)(?:/|$)"
                        + "|\\.(?:tmp|log|bak|swp|class|orig|rej)$"
                        + "|(?:Test|Tests|TestCase|Spec|IT)\\.(?:java|kt|scala)$");
        if (regex == null || regex.isBlank()) {
            return null;
        }
        try {
            return Pattern.compile(regex);
        } catch (RuntimeException e) {
            log.warn("file.delete.confirm.exempt.regex 非法，忽略豁免规则: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 向用户征求删除确认。复用 ask_user_question 同一套控制台问询器（提示走 stderr、并行加锁、EOF 返回 null）。
     * 非交互环境（{@code --prompt}/单测，answers==null）按 {@code file.delete.confirm.autoApprove} 兜底，默认拒绝。
     */
    private boolean confirmDelete(String rel, long size) {
        Question question = new Question(
                "即将永久删除文件：" + rel + "（" + humanSize(size) + "）。"
                        + "此操作不可逆（可事后用 /rewind 回溯还原）。确认删除吗？",
                "删除确认", false,
                List.of(new Question.Option("取消", "不删除，保留文件（默认）"),
                        new Question.Option(CONFIRM_LABEL, "永久删除该文件")));
        List<String> answers = prompter.ask(List.of(question));
        if (answers == null || answers.isEmpty()) {
            boolean auto = ConfigUtil.getBoolean("file.delete.confirm.autoApprove", false);
            log.warn("delete_file 非交互环境，按 file.delete.confirm.autoApprove={} 兜底", auto);
            return auto;
        }
        String answer = answers.get(0);
        return answer != null && answer.contains(CONFIRM_LABEL);
    }

    private long longConfig(String key, long defaultValue) {
        String value = ConfigUtil.get(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            log.warn("config {} is not a long: {}, fallback to {}", key, value, defaultValue);
            return defaultValue;
        }
    }

    private List<String> splitCsv(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /**
     * 计算相对 repo root 的展示路径；不在其内或解析失败时回退为绝对路径
     */
    private String relativize(Path root, Path file) {
        try {
            Path rel = root.relativize(file);
            String s = rel.toString();
            return s.startsWith("..") ? file.toString() : s;
        } catch (RuntimeException e) {
            return file.toString();
        }
    }

    private String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
