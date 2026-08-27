package com.szh.utils;

/**
 * @author demussong
 * @describe
 * @date 2026/8/27 14:22
 */
public class CommonUtils {

    public static String generateId() {
        return java.util.UUID.randomUUID().toString().replaceAll("-","");
    }
}
