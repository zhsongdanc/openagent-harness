package com.szh.utils;

import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author demussong
 * @describe classpath 下 properties 配置文件的统一读取入口，只负责“把文本读出来转成类型”，不含任何业务语义。
 * <p>
 * 1. 按文件名缓存 {@link Properties}，同一文件只加载一次；
 * 2. 查找顺序为 JVM 启动参数（-Dkey=value）优先于配置文件，便于临时切换配置而不改文件；
 * 3. 值为 null 或空白一律视为“未配置”，返回默认值；
 * 4. 只读 classpath，不支持外部绝对路径，保持与项目现有资源加载方式一致。
 * @date 2026/9/1
 */
@Slf4j
public class ConfigUtil {

    /**
     * 项目主配置文件
     */
    public static final String APPLICATION_FILE = "application.properties";

    private static final Map<String, Properties> CACHE = new ConcurrentHashMap<>();

    private ConfigUtil() {
    }

    /**
     * 加载并缓存指定 classpath 配置文件，文件不存在时返回空 Properties（只告警不抛异常）
     */
    public static Properties load(String fileName) {
        return CACHE.computeIfAbsent(fileName, ConfigUtil::doLoad);
    }

    /**
     * 读主配置文件的原始值，未配置返回 null
     */
    public static String get(String key) {
        return get(key, null);
    }

    /**
     * 读主配置文件的原始值，未配置返回默认值
     */
    public static String get(String key, String defaultValue) {
        return getFromFile(APPLICATION_FILE, key, defaultValue);
    }

    /**
     * 读指定配置文件的原始值，未配置返回 null
     */
    public static String getFromFile(String fileName, String key) {
        return getFromFile(fileName, key, null);
    }

    /**
     * 读指定配置文件的原始值，未配置返回默认值；启动参数 -Dkey=value 优先于文件
     */
    public static String getFromFile(String fileName, String key, String defaultValue) {
        String override = System.getProperty(key);
        if (isPresent(override)) {
            return override.trim();
        }
        String value = load(fileName).getProperty(key);
        return isPresent(value) ? value.trim() : defaultValue;
    }

    /**
     * 读主配置文件中的必填项，缺失直接抛异常（配置写错时尽早失败，而不是带着隐式默认值往下跑）
     */
    public static String require(String key) {
        return requireFromFile(APPLICATION_FILE, key);
    }

    /**
     * 读指定配置文件中的必填项，缺失直接抛异常
     */
    public static String requireFromFile(String fileName, String key) {
        String value = getFromFile(fileName, key);
        if (value == null) {
            throw new IllegalStateException("missing required config, key=" + key + ", file=" + fileName);
        }
        return value;
    }

    public static int getInt(String key, int defaultValue) {
        String value = get(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            log.warn("config {} is not an integer: {}, fallback to {}", key, value, defaultValue);
            return defaultValue;
        }
    }

    public static boolean getBoolean(String key, boolean defaultValue) {
        String value = get(key);
        return value == null ? defaultValue : Boolean.parseBoolean(value);
    }

    /**
     * 清空缓存，配置文件热更新或单测隔离时使用
     */
    public static void clearCache() {
        CACHE.clear();
    }

    private static Properties doLoad(String fileName) {
        Properties props = new Properties();
        try (InputStream in = openStream(fileName)) {
            if (in == null) {
                log.warn("config file not found in classpath: {}", fileName);
                return props;
            }
            props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            log.info("config file loaded: {}, size={}", fileName, props.size());
        } catch (Exception e) {
            log.error("load config file failed: {}", fileName, e);
        }
        return props;
    }

    private static InputStream openStream(String fileName) {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = ConfigUtil.class.getClassLoader();
        }
        InputStream in = loader.getResourceAsStream(fileName);
        return in != null ? in : ConfigUtil.class.getClassLoader().getResourceAsStream(fileName);
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }
}
