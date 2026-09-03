package com.szh.tool;

import com.szh.tool.tools.QueryLocationTool;
import com.szh.tool.tools.QueryWeatherTool;
import com.szh.tool.tools.shell.CatTool;
import com.szh.tool.tools.shell.FindTool;
import com.szh.tool.tools.shell.GitTool;
import com.szh.tool.tools.shell.GrepTool;
import com.szh.tool.tools.shell.HeadTool;
import com.szh.tool.tools.shell.ListFileTool;
import com.szh.tool.tools.shell.MvnTool;
import com.szh.tool.tools.shell.PwdTool;
import com.szh.tool.tools.shell.TailTool;

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
        allTools.addAll(shellTools);

        tools = List.copyOf(allTools);
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
