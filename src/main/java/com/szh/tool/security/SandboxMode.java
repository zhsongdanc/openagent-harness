package com.szh.tool.security;

/**
 * @author demussong
 * @describe 沙箱授权档位，对标 Codex CLI 的三档审批模型（read-only / workspace-write / danger-full-access）。
 * <p>
 * 档位决定命令的默认能力边界，越靠后放开越多：
 * <ul>
 *   <li>{@link #READ_ONLY}：只能读，禁止任何写操作与联网；</li>
 *   <li>{@link #WORKSPACE_WRITE}：可读写工作区（及少量缓存目录），默认禁网，联网命令需走豁免名单；</li>
 *   <li>{@link #FULL_ACCESS}：完全放开，等价于直接在宿主机执行（开发调试或明确信任场景）。</li>
 * </ul>
 * @date 2026/9/21
 */
public enum SandboxMode {

    /**
     * 只读：任何写文件或联网的命令都会被拒绝
     */
    READ_ONLY,

    /**
     * 工作区可写：默认档位，能改工作区内的文件，但默认禁止联网、禁止碰工作区外的文件
     */
    WORKSPACE_WRITE,

    /**
     * 完全放开：不做任何限制，直接在宿主机执行（沙箱降级）
     */
    FULL_ACCESS;

    /**
     * 宽松解析配置值：忽略大小写与连字符/下划线差异（workspace-write == WORKSPACE_WRITE），
     * 无法识别时退回 {@link #WORKSPACE_WRITE} 这一安全且够用的默认档位。
     */
    public static SandboxMode from(String value) {
        if (value == null || value.isBlank()) {
            return WORKSPACE_WRITE;
        }
        String normalized = value.trim().toUpperCase().replace('-', '_');
        for (SandboxMode mode : values()) {
            if (mode.name().equals(normalized)) {
                return mode;
            }
        }
        // 兼容 Codex 的 danger-full-access 命名
        if (normalized.contains("FULL")) {
            return FULL_ACCESS;
        }
        if (normalized.contains("READ")) {
            return READ_ONLY;
        }
        return WORKSPACE_WRITE;
    }
}
