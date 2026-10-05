package com.unibus.backend.staging;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(properties = "app.schema.validation.enabled=false")
@Sql("/sql/admin-api-schema.sql")
class StagingFixturesIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:15-alpine"));
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Autowired JdbcTemplate jdbc;
    @TempDir java.nio.file.Path temporary;

    private Map<String, String> environment() {
        return new HashMap<>(Map.of(
            "UNIBUS_TARGET_ENV", "staging", "STAGING_PROJECT_REF", StagingFixtures.PROJECT,
            "SUPABASE_API_URL", "https://" + StagingFixtures.PROJECT + ".supabase.co",
            "SUPABASE_DB_URL", "jdbc:postgresql://db." + StagingFixtures.PROJECT + ".supabase.co:5432/postgres?sslmode=verify-full",
            "SUPABASE_DB_USERNAME", "postgres", "STAGING_ADMIN_EMAIL", "admin@example.invalid",
            "STAGING_DRIVER_EMAIL", "driver@example.invalid", "STAGING_ADMIN_PASSWORD", "local-new-admin-password",
            "STAGING_DRIVER_PASSWORD", "local-new-driver-password"));
    }

    @Test
    void refusesProductionMisidentifiedPoolerAndUnverifiedTls() {
        var env = environment();
        StagingFixtures.validateTarget(env);
        env.put("SUPABASE_DB_URL", "jdbc:postgresql://db.otherproject.supabase.co:5432/postgres?sslmode=verify-full");
        assertThatIllegalArgumentException().isThrownBy(() -> StagingFixtures.validateTarget(env));
        env.put("SUPABASE_DB_URL", "jdbc:postgresql://aws-0-ap-northeast-2.pooler.supabase.com:5432/postgres?sslmode=verify-full");
        assertThatIllegalArgumentException().isThrownBy(() -> StagingFixtures.validateTarget(env));
        env.put("SUPABASE_DB_USERNAME", "postgres." + StagingFixtures.PROJECT);
        StagingFixtures.validateTarget(env);
        env.put("SUPABASE_DB_URL", env.get("SUPABASE_DB_URL").replace("verify-full", "require"));
        assertThatIllegalArgumentException().isThrownBy(() -> StagingFixtures.validateTarget(env));
    }

    @Test
    void failedDirectionCorrectionRollsBackNewFixturesWithoutTouchingExistingTokens() {
        var before = jdbc.queryForList("SELECT id, token FROM auth_tokens ORDER BY id");
        var env = environment();
        env.put("STAGING_COMMUTER_ROUTE_ID", "60000000-0000-0000-0000-000000000001");
        var transaction = new org.springframework.transaction.support.TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
        assertThatIllegalArgumentException().isThrownBy(() -> transaction.executeWithoutResult(status -> {
            StagingFixtures.create(jdbc, env);
            StagingFixtures.correctSelectedRoute(jdbc, env);
        }));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id = ?", Integer.class, StagingFixtures.ADMIN)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notices WHERE id = ?", Integer.class, StagingFixtures.NOTICE)).isZero();
        assertThat(jdbc.queryForList("SELECT id, token FROM auth_tokens ORDER BY id")).isEqualTo(before);
    }

    @Test
    void fixturesAreIdempotentKeepExistingUsersTokensAndCreateBcryptAccountsAndBothDirections() throws Exception {
        var beforeTokens = jdbc.queryForList("SELECT id, user_id, token, expires_at FROM auth_tokens ORDER BY id");
        var beforeUsers = jdbc.queryForList("SELECT * FROM users ORDER BY id");
        var env = environment();
        StagingFixtures.create(jdbc, env);
        String adminHash = jdbc.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, StagingFixtures.ADMIN);
        assertThat(adminHash).startsWith("$2b$10$");
        assertThat(new BCryptPasswordEncoder().matches(env.get("STAGING_ADMIN_PASSWORD"), adminHash)).isTrue();
        env.put("STAGING_ADMIN_PASSWORD", "must-not-rotate-existing-password");
        assertThatIllegalStateException().isThrownBy(() -> StagingFixtures.create(jdbc, env));
        env.put("STAGING_ADMIN_PASSWORD", "local-new-admin-password");
        StagingFixtures.create(jdbc, env);
        assertThat(jdbc.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, StagingFixtures.ADMIN)).isEqualTo(adminHash);
        assertThat(jdbc.queryForList("SELECT id, user_id, token, expires_at FROM auth_tokens ORDER BY id")).isEqualTo(beforeTokens);
        assertThat(jdbc.queryForList("SELECT * FROM users WHERE id NOT IN (?, ?) ORDER BY id", StagingFixtures.ADMIN, StagingFixtures.DRIVER)).isEqualTo(beforeUsers);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM route_stops WHERE route_id IN (?, ?)", Integer.class,
            StagingFixtures.TO_SCHOOL, StagingFixtures.FROM_SCHOOL)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT name FROM routes WHERE id = ?", String.class, StagingFixtures.TO_SCHOOL)).startsWith("[출발]");
        assertThat(jdbc.queryForObject("SELECT name FROM routes WHERE id = ?", String.class, StagingFixtures.FROM_SCHOOL)).startsWith("[도착]");
        assertThat(jdbc.queryForObject("SELECT assigned_driver_id FROM buses WHERE id = 'E2E-STAGING-01'", java.util.UUID.class)).isEqualTo(StagingFixtures.DRIVER);
        StagingFixtures.writeImage(temporary.resolve("fixture.png"));
        assertThat(javax.imageio.ImageIO.read(temporary.resolve("fixture.png").toFile()).getWidth()).isEqualTo(320);
        assertThat(Files.size(temporary.resolve("fixture.png"))).isGreaterThan(100);
    }

    @Test
    void onlyExplicitlySelectedCommuterRouteIsCorrectedWithoutGuessingDirection() {
        var env = environment();
        env.put("STAGING_COMMUTER_ROUTE_ID", "60000000-0000-0000-0000-000000000001");
        assertThatIllegalArgumentException().isThrownBy(() -> StagingFixtures.correctSelectedRoute(jdbc, env));
        env.put("STAGING_COMMUTER_REGION", "서울");
        env.put("STAGING_COMMUTER_DIRECTION", "from-school");
        StagingFixtures.correctSelectedRoute(jdbc, env);
        StagingFixtures.correctSelectedRoute(jdbc, env);
        assertThat(jdbc.queryForObject("SELECT name FROM routes WHERE id = '60000000-0000-0000-0000-000000000001'", String.class))
            .isEqualTo("[도착] 기존 노선");
        assertThat(jdbc.queryForObject("SELECT region FROM routes WHERE id = '60000000-0000-0000-0000-000000000001'", String.class)).isEqualTo("서울");
    }
}
