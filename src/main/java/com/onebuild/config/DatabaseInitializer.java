package com.onebuild.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 数据库结构版本初始化。
 *
 * 开发阶段不兼容旧表结构，schema.sql 每次启动按最新结构建表。
 */
@Component
public class DatabaseInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DatabaseInitializer.class);
    private static final int SCHEMA_VERSION = 1;

    private final JdbcTemplate jdbcTemplate;

    public DatabaseInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from schema_version where version = ?", Integer.class, SCHEMA_VERSION);
        if (count == null || count == 0) {
            jdbcTemplate.update("insert into schema_version(version) values (?)", SCHEMA_VERSION);
            log.info("初始化数据库 schema_version={}", SCHEMA_VERSION);
        }
    }
}
