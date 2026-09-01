package com.szh.store.db;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.toolkit.reflect.GenericTypeUtils;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;

import javax.sql.DataSource;
import java.io.InputStream;
import java.util.Properties;

/**
 * @author demussong
 * @describe 非 Spring 环境下手动构建 MyBatis-Plus SqlSessionFactory，全局单例复用
 * @date 2026/9/1
 */
@Slf4j
public class MyBatisPlusHolder {

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
        Properties props = new Properties();
        try (InputStream in = MyBatisPlusHolder.class.getClassLoader().getResourceAsStream("db.properties")) {
            if (in == null) {
                throw new IllegalStateException("db.properties not found in classpath");
            }
            props.load(in);
        } catch (Exception e) {
            throw new IllegalStateException("load db.properties failed", e);
        }

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(props.getProperty("db.url"));
        config.setUsername(props.getProperty("db.username"));
        config.setPassword(props.getProperty("db.password"));
        config.setMaximumPoolSize(8);
        config.setPoolName("agent-event-pool");
        return new HikariDataSource(config);
    }
}
