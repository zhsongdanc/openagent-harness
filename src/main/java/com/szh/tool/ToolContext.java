package com.szh.tool;

import com.szh.utils.ConfigUtil;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * @author demussong
 * @describe
 * @date 2026/9/2 21:11
 */
@Data
@AllArgsConstructor
public class ToolContext {
    // TODO 暂时不加sessionId、runId
    private String sessionId;
    private String runId;
    private String workspace;
    private String args;

    public ToolContext (String args) {
        this.workspace = ConfigUtil.get("project.workspace", System.getProperty("user.dir"));
        this.args = args;
    }
}
