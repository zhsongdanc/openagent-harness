package com.szh.tool.security;

import lombok.extern.slf4j.Slf4j;

/**
 * @author demussong
 * @describe 自动确认器：非交互场景（批处理、单测、后台服务）下按固定策略处理确认请求，不阻塞等待输入。
 * @date 2026/9/21
 */
@Slf4j
public class AutoPermissionPrompter implements PermissionPrompter {

    private final boolean autoApprove;

    public AutoPermissionPrompter(boolean autoApprove) {
        this.autoApprove = autoApprove;
    }

    @Override
    public boolean confirm(String prompt) {
        log.warn("auto permission decision={} for: {}", autoApprove, prompt);
        return autoApprove;
    }
}
