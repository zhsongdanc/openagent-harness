package com.szh.store.db;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.toolkit.reflect.GenericTypeUtils;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.szh.utils.ConfigUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;

import javax.sql.DataSource;

/**
 * @author demussong
 * @describe 非 Spring 环境下手动构建 MyBatis-Plus SqlSessionFactory，全局单例复用
 * @date 2026/9/1
 */
@Slf4j
public class MyBatisPlusHolder {

    private static final String DB_CONFIG_FILE = "db.properties";

    static {

        GenericTypeUtils.setGenericTypeResolver(new StandaloneGenericTypeResolver());    }

    private static volatile SqlSessionFactory sqlSessionFactory;

    public static SqlSessionFactory getSqlSessionFactory() {
        if (sqlSessionFactory == null) {
            synchronized (MyBatisPlusHolder.class) {
                if (sqlSessionFactory == null) {
                    sqlSessionFactory = build();
                }
            }
        }
        return sqlSessionFactory;
    }

    private static SqlSessionFactory build() {
        DataSource dataSource = buildDataSource();

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("default", new JdbcTransactionFactory(), dataSource));
        configuration.addMapper(AgentEventMapper.class);

        log.info("MyBatis-Plus SqlSessionFactory initialized");
        return new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    private static DataSource buildDataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(ConfigUtil.requireFromFile(DB_CONFIG_FILE, "db.url"));
        config.setUsername(ConfigUtil.requireFromFile(DB_CONFIG_FILE, "db.username"));
        config.setPassword(ConfigUtil.getFromFile(DB_CONFIG_FILE, "db.password", ""));
        config.setMaximumPoolSize(8);
        config.setPoolName("agent-event-pool");
        return new HikariDataSource(config);
    }
}
