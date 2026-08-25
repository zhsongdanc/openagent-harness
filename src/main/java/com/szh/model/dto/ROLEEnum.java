package com.szh.model.dto;

/**
 * @author demussong
 * @describe
 * @date 2026/8/25 17:45
 */
public enum ROLEEnum {
    USER("user"),
    ASSISTANT("assistant"),
    SYSTEM("system"),
    TOOL("tool"),
    ;

    private final String role;

    ROLEEnum(String role) {
        this.role = role;
    }

    public String getRole() {
        return role;
    }
}
