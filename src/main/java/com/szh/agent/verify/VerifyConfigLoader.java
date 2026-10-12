package com.szh.agent.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.szh.utils.CommonUtils;
import com.szh.utils.ConfigUtil;
import com.szh.utils.JsonUtil;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 验证闭环配置加载器（镜像 {@code McpConfigLoader} 的加载范式）。
 * <p>
 * 分层装配：
 * <ol>
 *   <li><b>基线</b>：从 {@code application.properties} 读调优旋钮
 *       （{@code agent.verify.enabled/maxFixRounds/timeoutSeconds/onlyWhenDirty}）；</li>
 *   <li><b>覆盖</b>：项目级 {@code {workspace}/.openagent/verify.json} 优先于全局
 *       {@code agent.verify.config.file}（默认 {@code ~/.openagent/verify.json}）；命中即用文件里的
 *       enabled/commands/maxFixRounds/timeoutSeconds/onlyWhenDirty 覆盖基线，验证命令只能由 json 提供。</li>
 * </ol>
 * 文件缺失或解析失败一律静默降级为「未启用」，绝不拖垮 run。命令串支持 {@code ~} 与 {@code ${VAR}} 展开。
 *
 * @author demussong
 * @date 2026/10/9
 */
@Slf4j
public final class VerifyConfigLoader {

    /** 默认全局配置文件路径（用户级） */
    public static final String DEFAULT_PATH = "~/.openagent/verify.json";

    /** 项目级配置文件相对工作区的路径 */
    private static final String PROJECT_RELATIVE = ".openagent/verify.json";

    private VerifyConfigLoader() {
    }

    /**
     * 加载验证配置；workspace 用于定位项目级 verify.json，可为 null（回退全局/properties）。
     */
    public static VerifyConfig load(String workspace) {
        VerifyConfig cfg = baseline();

        // 项目级优先，其次全局配置文件
        Path project = workspace == null || workspace.isBlank()
                ? null : Path.of(workspace).resolve(PROJECT_RELATIVE);
        Path global = Path.of(CommonUtils.expand(ConfigUtil.get("agent.verify.config.file", DEFAULT_PATH)));
        Path chosen = (project != null && Files.exists(project)) ? project
                : (Files.exists(global) ? global : null);

        if (chosen == null) {
            // 无 json：命令为空，验证天然无法执行（VerifyGate 会据此判为未武装）
            return cfg;
        }
        try {
            JsonNode root = JsonUtil.getMapper().readTree(chosen.toFile());
            applyJson(cfg, root);
            log.info("verify config loaded from {}: enabled={}, {} command(s)",
                    chosen, cfg.isEnabled(), cfg.getCommands().size());
        } catch (Exception e) {
            log.error("verify config load failed: {} (降级为未启用)", chosen, e);
        }
        return cfg;
    }

    /**
     * 从 application.properties 读调优旋钮作为基线（命令清单不在 properties 里，只能由 json 提供）
     */
    private static VerifyConfig baseline() {
        VerifyConfig cfg = new VerifyConfig();
        cfg.setEnabled(ConfigUtil.getBoolean("agent.verify.enabled", false));
        cfg.setMaxFixRounds(ConfigUtil.getInt("agent.verify.maxFixRounds", 3));
        cfg.setTimeoutSeconds(ConfigUtil.getInt("agent.verify.timeoutSeconds", 600));
        cfg.setOnlyWhenDirty(ConfigUtil.getBoolean("agent.verify.onlyWhenDirty", true));
        return cfg;
    }

    /**
     * 用 json 节点覆盖基线：仅覆盖 json 里显式出现的字段，未出现的保留 properties 值
     */
    private static void applyJson(VerifyConfig cfg, JsonNode root) {
        if (root == null || !root.isObject()) {
            return;
        }
        if (root.has("enabled")) {
            cfg.setEnabled(root.path("enabled").asBoolean(false));
        }
        if (root.has("maxFixRounds")) {
            cfg.setMaxFixRounds(root.path("maxFixRounds").asInt(cfg.getMaxFixRounds()));
        }
        if (root.has("timeoutSeconds")) {
            cfg.setTimeoutSeconds(root.path("timeoutSeconds").asLong(cfg.getTimeoutSeconds()));
        }
        if (root.has("onlyWhenDirty")) {
            cfg.setOnlyWhenDirty(root.path("onlyWhenDirty").asBoolean(true));
        }
        JsonNode cmds = root.path("commands");
        if (cmds.isArray()) {
            List<String> list = new ArrayList<>();
            for (JsonNode c : cmds) {
                String s = CommonUtils.expand(c.asText(null));
                if (s != null && !s.isBlank()) {
                    list.add(s.trim());
                }
            }
            cfg.setCommands(List.copyOf(list));
        }
    }
}
