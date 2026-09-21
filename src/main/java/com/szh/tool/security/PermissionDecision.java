package com.szh.tool.security;

/**
 * @author demussong
 * @describe 权限决策结果：放行 / 拒绝 / 需要人工确认，附带原因便于回传模型与打日志。
 * @date 2026/9/21
 */
public class PermissionDecision {

    public enum Type {
        /**
         * 放行，可直接执行
         */
        ALLOW,
        /**
         * 拒绝，不执行并把原因回传模型
         */
        DENY,
        /**
         * 需要人工确认（危险操作或越界访问），确认通过才执行
         */
        NEED_CONFIRM
    }

    private final Type type;
    private final String reason;

    private PermissionDecision(Type type, String reason) {
        this.type = type;
        this.reason = reason;
    }

    public static PermissionDecision allow() {
        return new PermissionDecision(Type.ALLOW, null);
    }

    public static PermissionDecision allow(String reason) {
        return new PermissionDecision(Type.ALLOW, reason);
    }

    public static PermissionDecision deny(String reason) {
        return new PermissionDecision(Type.DENY, reason);
    }

    public static PermissionDecision confirm(String reason) {
        return new PermissionDecision(Type.NEED_CONFIRM, reason);
    }

    public Type getType() {
        return type;
    }

    public String getReason() {
        return reason;
    }

    public boolean isAllowed() {
        return type == Type.ALLOW;
    }

    public boolean isDenied() {
        return type == Type.DENY;
    }

    public boolean needsConfirm() {
        return type == Type.NEED_CONFIRM;
    }
}
