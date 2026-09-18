package com.szh.tool;

import com.szh.utils.ConfigUtil;
import lombok.Data;

/**
 * @author demussong
 * @describe
 * @date 2026/9/2 21:11
 */
@Data
public class ToolContext {

    private String sessionId;
    private String runId;
    private String workspace;
    private String args;

    public ToolContext(String sessionId, String runId, String workspace, String args) {
        this.sessionId = sessionId;
        this.runId = runId;
        this.workspace = workspace != null ? workspace : ConfigUtil.get("project.workspace", System.getProperty("user.dir"));
        this.args = args;
    }

    public ToolContext(String args) {
        this.workspace = ConfigUtil.get("project.workspace", System.getProperty("user.dir"));
        this.args = args;
    }
}
