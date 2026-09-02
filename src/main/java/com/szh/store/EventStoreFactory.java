package com.szh.store;

import com.szh.utils.ConfigUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * @author demussong
 * @describe 事件存储工厂：把配置文件里的存储引擎标识翻译成项目要用的 {@link EventStore} 对象，
 * 引擎与实现类的映射关系集中在此，新增引擎只需扩枚举并补一个 case。
 * @date 2026/9/1
 */
@Slf4j
public class EventStoreFactory {

    /**
     * 存储引擎配置项，取值见 {@link StoreEnum}（大小写不敏感）
     */
    public static final String STORE_ENGINE_KEY = "store.engine";

    /**
     * 未配置时的兜底引擎
     */
    private static final StoreEnum DEFAULT_ENGINE = StoreEnum.MEMORY;

    private EventStoreFactory() {
    }

    /**
     * 读取配置并解析为当前使用的存储引擎类型：未配置走 {@link #DEFAULT_ENGINE}，配了非法值直接失败
     */
    public static StoreEnum getStoreEngine() {
        String value = ConfigUtil.get(STORE_ENGINE_KEY);
        if (value == null) {
            return DEFAULT_ENGINE;
        }
        return parse(value);
    }

    /**
     * 按配置的存储引擎创建对应的事件存储
     */
    public static EventStore createEventStore() {
        StoreEnum engine = getStoreEngine();
        log.info("create event store, engine={}", engine);
        return switch (engine) {
            case MEMORY -> new MemoryEventStore();
            case MYSQL -> new MySqlEventStore();
        };
    }

    private static StoreEnum parse(String value) {
        try {
            return StoreEnum.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            // 配置写错时不能静默降级到内存，否则事件会以为落库成功实则全部丢失
            throw new IllegalStateException("unsupported " + STORE_ENGINE_KEY + ": " + value
                    + ", valid values: [" + Arrays.stream(StoreEnum.values())
                    .map(Enum::name).collect(Collectors.joining(", ")) + "]", e);
        }
    }
}
