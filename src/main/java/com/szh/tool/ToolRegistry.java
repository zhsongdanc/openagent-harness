package com.szh.tool;

import com.szh.memory.LongTermMemory;
import com.szh.tool.tools.QueryLocationTool;
import com.szh.tool.tools.QueryWeatherTool;
import com.szh.tool.tools.ReadToolResultTool;
import com.szh.tool.tools.file.EditFileTool;
import com.szh.tool.tools.file.ReadFileTool;
import com.szh.tool.tools.file.RepoMapTool;
import com.szh.tool.tools.file.WriteFileTool;
import com.szh.tool.tools.memory.ForgetTool;
import com.szh.tool.tools.memory.RecallTool;
import com.szh.tool.tools.memory.RememberTool;
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

/**
 * @author demussong
 * @describe
 * @date 2026/8/25 12:09
 */
public class ToolRegistry {

    private List<Tool> tools;

    public ToolRegistry() {
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
        allTools.addAll(memoryTools());

        tools = List.copyOf(allTools);
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
     * 记忆工具（remember/recall/forget）：仅当长期记忆真正可用且未显式关闭时注册，
     * 让模型能像主流 harness 那样主动读写长期记忆；记忆关闭时不污染工具列表。
     */
    private List<Tool> memoryTools() {
        if (!ConfigUtil.getBoolean("memory.tools.enabled", true) || !LongTermMemory.get().isActive()) {
            return List.of();
        }
        return List.of(new RememberTool(), new RecallTool(), new ForgetTool());
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
