package com.szh.tool.security;

/**
 * @author demussong
 * @describe 人工确认器：当命令触发 NEED_CONFIRM（危险操作或越界访问）时，向使用方征求批准。
 * <p>
 * 交互式运行用 {@link ConsolePermissionPrompter}（从标准输入读 y/n）；
 * 非交互场景（批处理、单测）用 {@link AutoPermissionPrompter} 按配置固定放行或拒绝。
 * @date 2026/9/21
 */
public interface PermissionPrompter {

    /**
     * 征求确认
     *
     * @param prompt 展示给用户的说明（命令内容 + 触发原因）
     * @return true 表示批准执行，false 表示拒绝
     */
    boolean confirm(String prompt);
}
