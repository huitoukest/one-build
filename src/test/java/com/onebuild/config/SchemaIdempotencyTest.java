package com.onebuild.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaIdempotencyTest {

    @Test
    void schemaSqlCanRunTwice() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:schema_idem;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        ResourceDatabasePopulator populator =
                new ResourceDatabasePopulator(new ClassPathResource("schema.sql"));

        DatabasePopulatorUtils.execute(populator, dataSource);
        DatabasePopulatorUtils.execute(populator, dataSource);

        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        Integer tables = jdbcTemplate.queryForObject(
                "select count(*) from information_schema.tables "
                        + "where table_schema = 'PUBLIC' and table_name = 'PROJECT'", Integer.class);
        assertTrue(tables != null && tables > 0);
        Integer indexes = jdbcTemplate.queryForObject(
                "select count(*) from information_schema.indexes "
                        + "where index_name = 'UK_TRIGGER_CONFIG_BATCH'", Integer.class);
        assertEquals(1, indexes);
    }
}
