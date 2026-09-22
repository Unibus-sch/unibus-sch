package com.unibus.backend.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class DatabaseSchemaValidatorTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
        DockerImageName.parse("postgres:15-alpine")
    );

    private JdbcTemplate jdbcTemplate;
    private DatabaseSchemaValidator validator;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()
        );
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public");
        SchemaValidationProperties properties = new SchemaValidationProperties();
        validator = new DatabaseSchemaValidator(jdbcTemplate, properties);
    }

    @Test
    void acceptsTheCompleteRequiredSchemaWithoutChangingIt() {
        createRequiredSchema();

        assertThatCode(validator::validate).doesNotThrowAnyException();
    }

    @Test
    void reportsMissingTablesColumnsAndRoutinesBeforeServingTraffic() {
        createRequiredSchema();
        jdbcTemplate.execute("ALTER TABLE bus_latest_state DROP COLUMN last_history_at");
        jdbcTemplate.execute("DROP TABLE notification_deliveries");
        jdbcTemplate.execute("DROP FUNCTION record_bus_location(varchar, uuid, numeric, numeric, numeric, integer)");

        assertThatThrownBy(validator::validate)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("column public.bus_latest_state.last_history_at")
            .hasMessageContaining("table public.notification_deliveries")
            .hasMessageContaining("routine public.record_bus_location")
            .hasMessageContaining("No schema changes were attempted");
    }

    private void createRequiredSchema() {
        DatabaseSchemaValidator.REQUIRED_COLUMNS.forEach((table, columns) -> {
            String definitions = columns.stream()
                .map(column -> column + " TEXT")
                .collect(Collectors.joining(", "));
            jdbcTemplate.execute("CREATE TABLE " + table + " (" + definitions + ")");
        });
        jdbcTemplate.execute("""
            CREATE FUNCTION consume_api_rate_limit(text, integer, integer)
            RETURNS boolean LANGUAGE sql AS $$ SELECT true $$
            """);
        jdbcTemplate.execute("""
            CREATE FUNCTION replace_route_details(uuid, boolean, jsonb, boolean, jsonb)
            RETURNS void LANGUAGE sql AS $$ SELECT NULL::void $$
            """);
        jdbcTemplate.execute("""
            CREATE FUNCTION record_bus_location(varchar, uuid, numeric, numeric, numeric, integer)
            RETURNS boolean LANGUAGE sql AS $$ SELECT true $$
            """);
        jdbcTemplate.execute("""
            CREATE FUNCTION admin_force_stop_bus(varchar)
            RETURNS void LANGUAGE sql AS $$ SELECT NULL::void $$
            """);
    }
}
