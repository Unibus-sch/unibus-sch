package com.unibus.backend.staging;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit operator CLI, never a Spring bean or an HTTP endpoint. */
public final class StagingFixtures {
    public static final String PROJECT = "srxzkpdtxmqrtcxgpeyl";
    public static final UUID ADMIN = UUID.fromString("e2e00000-0000-4000-8000-000000000001");
    public static final UUID DRIVER = UUID.fromString("e2e00000-0000-4000-8000-000000000002");
    public static final UUID TO_SCHOOL = UUID.fromString("e2e00000-0000-4000-8000-000000000011");
    public static final UUID FROM_SCHOOL = UUID.fromString("e2e00000-0000-4000-8000-000000000012");
    public static final UUID NOTICE = UUID.fromString("e2e00000-0000-4000-8000-000000000021");

    private StagingFixtures() { }

    public static void main(String[] args) {
        try { run(); }
        catch (Exception error) {
            // Never dump a driver exception, connection URL, hash, password or account data.
            System.err.println("Staging fixture preparation failed. Check target, secret configuration, schema and fixture ID collisions; no secrets are logged.");
            System.exit(1);
        }
    }

    private static void run() throws Exception {
        Map<String, String> env = System.getenv();
        if (!"apply".equals(env.get("STAGING_FIXTURE_MODE"))) {
            writeImage(Path.of("build/staging-fixtures/notice-image.png"));
            System.out.println("Preparation only: supply staging credentials and STAGING_FIXTURE_MODE=apply to create fixtures.");
            return;
        }
        validateTarget(env);
        var source = new DriverManagerDataSource(required(env, "SUPABASE_DB_URL"),
            required(env, "SUPABASE_DB_USERNAME"), required(env, "SUPABASE_DB_PASSWORD"));
        var jdbc = new JdbcTemplate(source);
        // All relational fixtures and any explicitly selected route correction commit together.
        new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(status -> {
            create(jdbc, env);
            correctSelectedRoute(jdbc, env);
        });
        Path output = Path.of("build/staging-fixtures/notice-image.png");
        writeImage(output);
        System.out.println("Staging fixtures ready: 2 accounts, 2 routes, 1 bus, 1 notice. Existing accounts/tokens preserved.");
        System.out.println("Image prepared at build/staging-fixtures/notice-image.png; upload through Spring /notices/images.");
    }

    public static void validateTarget(Map<String, String> env) {
        if (!"staging".equals(env.get("UNIBUS_TARGET_ENV"))
            || !PROJECT.equals(env.get("STAGING_PROJECT_REF"))
            || !("https://" + PROJECT + ".supabase.co").equals(env.get("SUPABASE_API_URL"))) {
            throw new IllegalArgumentException("Explicit staging project confirmation is required");
        }
        String raw = required(env, "SUPABASE_DB_URL");
        java.net.URI uri = java.net.URI.create(raw.replaceFirst("^jdbc:", ""));
        String user = required(env, "SUPABASE_DB_USERNAME");
        boolean direct = ("db." + PROJECT + ".supabase.co").equals(uri.getHost());
        boolean pooler = uri.getHost() != null && uri.getHost().endsWith(".pooler.supabase.com")
            && ("postgres." + PROJECT).equals(user);
        if (!"postgresql".equals(uri.getScheme()) || (!direct && !pooler) || uri.getUserInfo() != null
            || !"/postgres".equals(uri.getPath()) || uri.getQuery() == null
            || !java.util.Arrays.asList(uri.getQuery().split("&")).contains("sslmode=verify-full")) {
            throw new IllegalArgumentException("Database must identify the staging project and use verified TLS; credentials belong in separate variables");
        }
        String admin = required(env, "STAGING_ADMIN_EMAIL");
        String driver = required(env, "STAGING_DRIVER_EMAIL");
        if (!admin.endsWith("@example.invalid") || !driver.endsWith("@example.invalid") || admin.equalsIgnoreCase(driver)) {
            throw new IllegalArgumentException("Distinct synthetic @example.invalid fixture emails are required");
        }
        if (required(env, "STAGING_ADMIN_PASSWORD").length() < 16
            || required(env, "STAGING_DRIVER_PASSWORD").length() < 16
            || env.get("STAGING_ADMIN_PASSWORD").getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72
            || env.get("STAGING_DRIVER_PASSWORD").getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72
            || env.get("STAGING_ADMIN_PASSWORD").startsWith("NEW_UNIQUE_")
            || env.get("STAGING_DRIVER_PASSWORD").startsWith("NEW_UNIQUE_")
            || env.get("STAGING_ADMIN_PASSWORD").equals(env.get("STAGING_DRIVER_PASSWORD"))) {
            throw new IllegalArgumentException("Distinct new staging passwords of at least 16 characters are required");
        }
    }

    public static void create(JdbcTemplate jdbc, Map<String, String> env) {
        // These reserved identifiers must not belong to unrelated rows.
        for (UUID id : java.util.List.of(TO_SCHOOL, FROM_SCHOOL)) {
            var names = jdbc.queryForList("SELECT name FROM routes WHERE id = ?", String.class, id);
            if (!names.isEmpty() && !names.getFirst().contains("[E2E]")) throw new IllegalStateException("Fixture route collision");
        }
        var busNames = jdbc.queryForList("SELECT name FROM buses WHERE id = 'E2E-STAGING-01'", String.class);
        if (!busNames.isEmpty() && !busNames.getFirst().startsWith("[E2E]")) throw new IllegalStateException("Fixture bus collision");
        var noticeTitles = jdbc.queryForList("SELECT title FROM notices WHERE id = ?", String.class, NOTICE);
        if (!noticeTitles.isEmpty() && !noticeTitles.getFirst().startsWith("[E2E]")) throw new IllegalStateException("Fixture notice collision");
        createAccount(jdbc, ADMIN, required(env, "STAGING_ADMIN_EMAIL"), required(env, "STAGING_ADMIN_PASSWORD"), "admin");
        createAccount(jdbc, DRIVER, required(env, "STAGING_DRIVER_EMAIL"), required(env, "STAGING_DRIVER_PASSWORD"), "driver");
        for (var route : Map.of(TO_SCHOOL, "[출발] [E2E] 서울역 → 학교", FROM_SCHOOL, "[도착] [E2E] 학교 → 서울역").entrySet()) {
            jdbc.update("""
                INSERT INTO routes (id, name, type, description, region, schedule, color, is_active)
                VALUES (?, ?, 'commute', 'Synthetic staging E2E fixture; not a real timetable', '서울', ?, '#1E3B8A', true)
                ON CONFLICT (id) DO NOTHING
                """, route.getKey(), route.getValue(), route.getKey().equals(TO_SCHOOL) ? "07:30" : "18:10");
            for (int index = 1; index <= 2; index++) {
                UUID stopId = UUID.fromString(route.getKey().equals(TO_SCHOOL)
                    ? "e2e00000-0000-4000-8000-00000000003" + index
                    : "e2e00000-0000-4000-8000-00000000004" + index);
                boolean school = route.getKey().equals(TO_SCHOOL) ? index == 2 : index == 1;
                jdbc.update("""
                    INSERT INTO route_stops (id, route_id, stop_name, stop_order, latitude, longitude)
                    VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (id) DO NOTHING
                    """, stopId, route.getKey(), school ? "학교" : "서울역", index,
                    school ? 36.769014 : 37.5547, school ? 126.927978 : 126.9706);
            }
        }
        jdbc.update("""
            INSERT INTO buses (id, name, type, capacity, status, is_running, assigned_driver_id, current_route_id)
            VALUES ('E2E-STAGING-01', '[E2E] 기사 테스트 버스', 'commute', 45, 'active', false, ?, ?)
            ON CONFLICT (id) DO NOTHING
            """, DRIVER, TO_SCHOOL);
        jdbc.update("""
            INSERT INTO notices (id, title, content, category, priority, author_id)
            VALUES (?, '[E2E] 스테이징 테스트 공지', '공지 CRUD·이미지 업로드 검증용 합성 데이터', 'general', 'low', ?)
            ON CONFLICT (id) DO NOTHING
            """, NOTICE, ADMIN);
    }

    private static void createAccount(JdbcTemplate jdbc, UUID id, String email, String password, String role) {
        var existing = jdbc.query("SELECT email, role, provider, password_hash FROM users WHERE id = ?",
            (rs, row) -> Map.of("email", rs.getString("email"), "role", rs.getString("role"),
                "provider", rs.getString("provider"), "hash", rs.getString("password_hash") == null ? "" : rs.getString("password_hash")), id);
        if (!existing.isEmpty()) {
            var row = existing.getFirst();
            if (!email.equals(row.get("email")) || !role.equals(row.get("role")) || !"local".equals(row.get("provider"))) {
                throw new IllegalStateException("Fixture account ID collision; nothing may be overwritten");
            }
            if (!new BCryptPasswordEncoder().matches(password, row.get("hash"))) {
                throw new IllegalStateException("Fixture password differs; use the original fixture secret rather than rotating it");
            }
            return; // Never rotate passwords or invalidate sessions when re-running preparation.
        }
        String hash = new BCryptPasswordEncoder(BCryptPasswordEncoder.BCryptVersion.$2B, 10).encode(password);
        jdbc.update("""
            INSERT INTO users (id, email, password_hash, name, role, provider)
            VALUES (?, ?, ?, ?, ?, 'local')
            """, id, email, hash, "[E2E] " + role, role);
    }

    public static void correctSelectedRoute(JdbcTemplate jdbc, Map<String, String> env) {
        String selected = env.get("STAGING_COMMUTER_ROUTE_ID");
        if (selected == null || selected.isBlank()) return;
        UUID routeId = UUID.fromString(selected);
        String region = required(env, "STAGING_COMMUTER_REGION").trim();
        String direction = required(env, "STAGING_COMMUTER_DIRECTION");
        if (region.isEmpty() || region.length() > 100 || !java.util.Set.of("to-school", "from-school").contains(direction)) {
            throw new IllegalArgumentException("Explicit region and to-school/from-school direction are required");
        }
        var rows = jdbc.query("SELECT name, type FROM routes WHERE id = ? FOR UPDATE",
            (rs, row) -> Map.of("name", rs.getString("name"), "type", rs.getString("type")), routeId);
        if (rows.size() != 1 || !java.util.Set.of("commute", "commuter").contains(rows.getFirst().get("type"))) {
            throw new IllegalArgumentException("Selected commuter route does not exist");
        }
        String prefix = direction.equals("to-school") ? "[출발] " : "[도착] ";
        String name = prefix + rows.getFirst().get("name").replaceFirst("^\\[(출발|도착)\\]\\s*", "");
        if (name.length() > 100) throw new IllegalArgumentException("Corrected route name exceeds 100 characters");
        jdbc.update("UPDATE routes SET name = ?, region = ?, updated_at = NOW() WHERE id = ?", name, region, routeId);
    }

    public static void writeImage(Path output) throws Exception {
        Files.createDirectories(output.toAbsolutePath().getParent());
        BufferedImage image = new BufferedImage(320, 160, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(30, 59, 138)); graphics.fillRect(0, 0, 320, 160);
            graphics.setColor(Color.WHITE); graphics.drawString("UNIBUS STAGING E2E", 30, 80);
        } finally { graphics.dispose(); }
        ImageIO.write(image, "png", output.toFile());
    }

    private static String required(Map<String, String> env, String name) {
        String value = env.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
