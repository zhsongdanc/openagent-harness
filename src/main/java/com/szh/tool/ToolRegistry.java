package com.szh.tool;

import com.szh.mcp.client.McpClient;
import com.szh.mcp.client.McpClientManager;
import com.szh.mcp.client.McpToolInfo;
import com.szh.mcp.tool.McpTool;
import com.szh.memory.LongTermMemory;
import com.szh.tool.tools.QueryLocationTool;
import com.szh.tool.tools.QueryWeatherTool;
import com.szh.tool.tools.ReadToolResultTool;
import com.szh.tool.tools.file.EditFileTool;
import com.szh.tool.tools.file.ReadFileTool;
import com.szh.tool.tools.file.RepoMapTool;
import com.szh.tool.tools.file.WriteFileTool;
import com.szh.tool.tools.git.GitAddTool;
import com.szh.tool.tools.git.GitCommitTool;
import com.szh.tool.tools.git.GitPushTool;
import com.szh.tool.tools.git.GitStatusTool;
import com.szh.tool.tools.memory.ForgetTool;
import com.szh.tool.tools.memory.RecallTool;
import com.szh.tool.tools.memory.RememberTool;
import com.szh.tool.tools.meta.AskUserQuestionTool;
import com.szh.tool.tools.meta.SwitchModeTool;
import com.szh.tool.tools.meta.TodoWriteTool;
import com.szh.tool.tools.subagent.DispatchSubAgentTool;
import com.szh.tool.tools.shell.CatTool;
import com.szh.tool.tools.shell.FindTool;
import com.szh.tool.tools.shell.GitTool;
import com.szh.tool.tools.shell.GrepTool;
import com.szh.tool.tools.shell.HeadTool;
import com.szh.tool.tools.shell.ListFileTool;
import com.szh.tool.tools.shell.MvnTool;
import com.szh.tool.tools.shell.PwdTool;
import com.szh.tool.tools.shell.TailTool;
import com.szh.utils.ConfigUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * @author demussong
 * @describe
 * @date 2026/8/25 12:09
 */
public class ToolRegistry {

    private List<Tool> tools;

    /**
     * 工具白名单：null 表示放开全部工具；非 null 时 rebuild 后只保留 code 命中的工具。
     * 子 agent（{@code SubAgentExecutor}）用按类型定制的白名单构造 registry，实现「独立工具子集」。
     */
    private final Set<String> allowedCodes;

    /**
     * 当前 registry 所处的子 agent 派生深度：主 agent 为 0，子 agent 逐层 +1。
     * 达到 {@code subagent.maxDepth} 时不再注册 dispatch_subagent，从结构上杜绝无限递归派生。
     */
    private final int subAgentDepth;

    public ToolRegistry() {
        this(null, 0);
    }

    /**
     * 构造带白名单与深度的 registry（供子 agent 使用）。
     *
     * @param allowedCodes  工具 code 白名单，null 表示不限
     * @param subAgentDepth 派生深度，主 agent 传 0
     */
    public ToolRegistry(Set<String> allowedCodes, int subAgentDepth) {
        this.allowedCodes = allowedCodes;
        this.subAgentDepth = subAgentDepth;
        rebuild();
    }

    /**
     * 重新扫描全部工具源（内置 + MCP），用于会话中 {@code /mcp reload} 后把新 Server 的工具拉进来。
     * <p>
     * 旧 tools 列表被新列表原子替换，不影响正在并发读取的调用方（最多看到旧快照，下一轮就新了）。
     */
    public synchronized void rebuild() {
        QueryLocationTool locationTool = new QueryLocationTool();
        QueryWeatherTool weatherTool = new QueryWeatherTool();
        ReadToolResultTool readToolResultTool = new ReadToolResultTool();

        // shell 工具的定义内置在各自工具类中，这里只负责注册，按需增删
        List<Tool> shellTools = List.of(
                new PwdTool(),
                new ListFileTool(),
                new FindTool(),
                new CatTool(),
                new HeadTool(),
                new TailTool(),
                new GrepTool(),
                new GitTool(),
                new MvnTool());

        List<Tool> allTools = new ArrayList<>();
        allTools.add(locationTool);
        allTools.add(weatherTool);
        allTools.add(readToolResultTool);
        allTools.addAll(shellTools);
        allTools.addAll(fileTools());
        allTools.addAll(gitTools());
        allTools.addAll(memoryTools());
        allTools.addAll(metaTools());
        allTools.addAll(subAgentTools());
        allTools.addAll(mcpTools());

        // 白名单过滤：子 agent 只保留其类型允许的工具 code；主 registry（allowedCodes=null）全量保留
        List<Tool> effective = allowedCodes == null
                ? allTools
                : allTools.stream().filter(t -> allowedCodes.contains(t.getCode())).toList();
        tools = List.copyOf(effective);
    }

    /**
     * 文件工具（read/write/edit/repo_map）：模型直接读写工作区文件与感知项目结构，
     * 是完成实际编码任务的基础能力；路径安全由 FileToolSupport 统一把关，
     * file.tools.enabled=false 可整体关闭退回纯 shell 工具链
     */
    private List<Tool> fileTools() {
        if (!ConfigUtil.getBoolean("file.tools.enabled", true)) {
            return List.of();
        }
        return List.of(new ReadFileTool(), new WriteFileTool(), new EditFileTool(), new RepoMapTool());
    }

    /**
     * git 工作流工具（git_status/git_add/git_commit/git_push）：把高频提交操作封装成参数结构化的独立工具，
     * 修复裸 git commit 弹编辑器阻塞、缺身份/凭据导致的“无法提交代码”。原始 git 透传工具仍保留在 shellTools 中，
     * 供 diff/log/show 等只读查询使用；git.tools.enabled=false 可整体关闭这组封装。
     */
    private List<Tool> gitTools() {
        if (!ConfigUtil.getBoolean("git.tools.enabled", true)) {
            return List.of();
        }
        return List.of(new GitStatusTool(), new GitAddTool(), new GitCommitTool(), new GitPushTool());
    }

    /**
     * 记忆工具（remember/recall/forget）：仅当长期记忆真正可用且未显式关闭时注册，
     * 让模型能像主流 harness 那样主动读写长期记忆；记忆关闭时不污染工具列表。
     */
    private List<Tool> memoryTools() {
        if (!ConfigUtil.getBoolean("memory.tools.enabled", true) || !LongTermMemory.get().isActive()) {
            return List.of();
        }
        return List.of(new RememberTool(), new RecallTool(), new ForgetTool());
    }

    /**
     * 元工具（todo_write / ask_user_question / switch_mode）：agent 自组织能力的基础——
     * 自己维护多步任务清单、结构化问询用户、在 NORMAL/PLAN 模式间切换。
     * meta.tools.enabled=false 可整体关闭退回纯执行型 agent。
     */
    private List<Tool> metaTools() {
        if (!ConfigUtil.getBoolean("meta.tools.enabled", true)) {
            return List.of();
        }
        return List.of(new TodoWriteTool(), new AskUserQuestionTool(), new SwitchModeTool());
    }

    /**
     * 子 agent 派生工具（dispatch_subagent，对标 Claude Code 的 Task）：让主 agent 把会产生大量中间产物的
     * 子任务外包给独立上下文的子 agent，只回收最终结论，是长任务不炸上下文的核心手段。
     * <p>
     * 深度闸门：{@code subagent.enabled=false} 整体关闭；当前 registry 深度达到 {@code subagent.maxDepth}
     * 时不再注册——即子 agent 默认拿不到 dispatch_subagent，无法再派生孙 agent，从结构上杜绝无限递归。
     */
    private List<Tool> subAgentTools() {
        if (!ConfigUtil.getBoolean("subagent.enabled", true)) {
            return List.of();
        }
        int maxDepth = Math.max(1, ConfigUtil.getInt("subagent.maxDepth", 1));
        if (subAgentDepth >= maxDepth) {
            return List.of();
        }
        return List.of(new DispatchSubAgentTool(subAgentDepth));
    }

    /**
     * MCP 工具：把外部 MCP Server 暴露的 tools 动态注册进来，让 openagent 可以直接吃到整个 MCP 生态
     * （filesystem / github / slack / postgres / playwright 等）。
     * <p>
     * 关键点：
     * <ol>
     *   <li>{@link McpClientManager#ensureInit()} 是幂等懒启动，本方法调用时才真正拉起 Server 子进程；</li>
     *   <li>启动失败/未配置时静默返回空列表，不影响其它工具注册；</li>
     *   <li>工具 code 加 {@code {server}__} 前缀做命名空间隔离（{@link McpTool}）；</li>
     *   <li>配置项 {@code mcp.enabled=false} 或 {@code mcp.json} 不存在时整组不注册。</li>
     * </ol>
     */
    private List<Tool> mcpTools() {
        if (!ConfigUtil.getBoolean("mcp.enabled", true)) {
            return List.of();
        }
        try {
            McpClientManager manager = McpClientManager.get();
            manager.ensureInit();
            List<Tool> result = new ArrayList<>();
            for (McpClient client : manager.getClients()) {
                for (McpToolInfo info : client.getTools()) {
                    result.add(new McpTool(client, info));
                }
            }
            return result;
        } catch (Exception e) {
            // MCP 装配失败绝不能拖垮整个工具注册；仅记录，让内置工具照常工作
            return List.of();
        }
    }


    public List<Tool> getTools() {
        return tools;
    }

    public Tool getToolByCode(String toolCode) {
        for (Tool tool : tools) {
            if (tool.getCode().equals(toolCode)) {
                return tool;
            }
        }
        return null;
    }
}
