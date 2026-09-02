package com.szh.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

/**
 * JSON 序列化工具，统一走一个 ObjectMapper 实例，异常内部消化并打印日志
 *
 * @author demussong
 * @date 2026/9/1
 */
@Slf4j
public class JsonUtil {

    /**
     * ObjectMapper 线程安全，全局复用一份；反序列化忽略未知字段，避免模型返回体新增字段导致解析失败
     */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private JsonUtil() {
    }

    public static ObjectMapper getMapper() {
        return MAPPER;
    }

    /**
     * 对象转 json 字符串，失败返回 null
     */
    public static String toJson(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.error("json serialize failed, type={}", obj.getClass().getName(), e);
            return null;
        }
    }

    /**
     * 对象转 pretty json 字符串，失败返回 null
     */
    public static String toPrettyJson(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.error("json serialize failed, type={}", obj.getClass().getName(), e);
            return null;
        }
    }

    /**
     * json 字符串转对象，失败返回 null
     */
    public static <T> T parse(String json, Class<T> clazz) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, clazz);
        } catch (Exception e) {
            log.error("json deserialize failed, target={}, json={}", clazz.getName(), json, e);
            return null;
        }
    }

    /**
     * json 字符串转泛型对象，如 List&lt;MessageItem&gt;，失败返回 null
     */
    public static <T> T parse(String json, TypeReference<T> typeReference) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, typeReference);
        } catch (Exception e) {
            log.error("json deserialize failed, target={}, json={}", typeReference.getType(), json, e);
            return null;
        }
    }

    /**
     * json 字符串转 JsonNode，便于按路径取值，失败返回 null
     */
    public static JsonNode readTree(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            log.error("json readTree failed, json={}", json, e);
            return null;
        }
    }

    /**
     * 对象之间转换（如 Map 转 PO），失败返回 null
     */
    public static <T> T convert(Object from, Class<T> clazz) {
        if (from == null) {
            return null;
        }
        try {
            return MAPPER.convertValue(from, clazz);
        } catch (Exception e) {
            log.error("json convert failed, from={}, target={}", from.getClass().getName(), clazz.getName(), e);
            return null;
        }
    }
}
