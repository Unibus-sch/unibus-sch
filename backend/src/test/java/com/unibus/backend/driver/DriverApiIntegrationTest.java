package com.unibus.backend.driver;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "app.schema.validation.enabled=false"
)
@Sql("/sql/driver-api-schema.sql")
class DriverApiIntegrationTest {

    private static final String ADMIN_TOKEN = "admin-session-token";
    private static final String USER_TOKEN = "user-session-token";
    private static final String DRIVER_ONE_TOKEN = "driver-one-token";
    private static final String DRIVER_TWO_TOKEN = "driver-two-token";
    private static final String EXPIRED_TOKEN = "expired-driver-token";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
        DockerImageName.parse("postgres:15-alpine"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void preservesDriverPermissionErrorsAndFiltersAvailableBuses() throws Exception {
        assertRawError(request("GET", "/driver/buses", null, null), 401,
            "{\"error\":\"Unauthorized: No token provided\"}");
        assertRawError(request("GET", "/driver/buses", null, "invalid-token"), 401,
            "{\"error\":\"Unauthorized: Invalid token\"}");
        assertRawError(request("GET", "/driver/buses", null, EXPIRED_TOKEN), 401,
            "{\"error\":\"Unauthorized: Token expired\"}");
        assertRawError(request("GET", "/driver/buses", null, USER_TOKEN), 403,
            "{\"error\":\"Forbidden: Driver or Admin access required\"}");

        ApiResult result = request("GET", "/driver/buses", null, DRIVER_ONE_TOKEN);
        assertThat(result.status()).isEqualTo(200);
        assertThat(result.json().path("data").size()).isEqualTo(2);
        assertThat(result.json().path("data").toString()).contains("SH-CONCURRENT", "SH-ASSIGNED");
        assertThat(result.json().path("data").toString()).doesNotContain("SH-OTHER", "CM-INACTIVE");
        assertThat(result.json().path("data").path(0).has("is_running")).isTrue();

        assertThat(request("GET", "/driver/buses", null, ADMIN_TOKEN).status()).isEqualTo(200);
    }

    @Test
    void allowsOnlyOneDriverToStartTheSameBusConcurrently() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ApiResult> first = executor.submit(() -> concurrentStart(ready, start, DRIVER_ONE_TOKEN));
            Future<ApiResult> second = executor.submit(() -> concurrentStart(ready, start, DRIVER_TWO_TOKEN));
            ready.await();
            start.countDown();

            List<ApiResult> results = List.of(first.get(), second.get());
            assertThat(results.stream().map(ApiResult::status).collect(java.util.stream.Collectors.toSet()))
                .isEqualTo(Set.of(200, 409));
            ApiResult conflict = results.stream().filter(value -> value.status() == 409).findFirst().orElseThrow();
            assertThat(conflict.json().toString()).isEqualTo(
                "{\"success\":false,\"error\":\"이미 다른 기사가 운행 중인 버스입니다\"}"
            );
            assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bus_trips WHERE status = 'active'", Integer.class)).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM buses WHERE is_running = TRUE", Integer.class)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void serializesConcurrentStartsByOneDriverAndKeepsOneActiveTrip() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ApiResult> first = executor.submit(() -> concurrentStart(
                ready, start, DRIVER_ONE_TOKEN, "SH-CONCURRENT"));
            Future<ApiResult> second = executor.submit(() -> concurrentStart(
                ready, start, DRIVER_ONE_TOKEN, "SH-ASSIGNED"));
            ready.await();
            start.countDown();

            assertThat(List.of(first.get().status(), second.get().status())).containsOnly(200);
            assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM bus_trips
                WHERE driver_id = '50000000-0000-0000-0000-000000000003' AND status = 'active'
                """, Integer.class)).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM buses
                WHERE current_driver_id = '50000000-0000-0000-0000-000000000003' AND is_running = TRUE
                """, Integer.class)).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bus_trips WHERE status = 'completed'", Integer.class)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void validatesAndPersistsGpsWithLatestStateAndSampledHistory() throws Exception {
        assertThat(startAssignedBus().status()).isEqualTo(200);
        assertApiError(request("POST", "/driver/location", "{\"lat\":91,\"lng\":127}",
            DRIVER_ONE_TOKEN), 400, "올바른 GPS 좌표가 필요합니다");

        ApiResult first = request("POST", "/driver/location", """
            {"lat":36.7691,"lng":126.9512,"speed":99,"heading":-10}
            """, DRIVER_ONE_TOKEN);
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.json().path("data").path("busId").stringValue()).isEqualTo("SH-ASSIGNED");

        ApiResult second = request("POST", "/driver/location", """
            {"lat":36.7701,"lng":126.9522,"speed":12.5,"heading":20}
            """, DRIVER_ONE_TOKEN);
        assertThat(second.status()).isEqualTo(200);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT latitude::double precision FROM bus_latest_state WHERE bus_id = 'SH-ASSIGNED'",
            Double.class)).isEqualTo(36.7701);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT speed::double precision FROM bus_latest_state WHERE bus_id = 'SH-ASSIGNED'",
            Double.class)).isEqualTo(12.5);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM bus_locations WHERE bus_id = 'SH-ASSIGNED'", Integer.class))
            .isEqualTo(1);
    }

    @Test
    void restoresActiveTripAfterReentryAndPersistsPhaseAndRouteTransition() throws Exception {
        ApiResult started = startAssignedBus();
        assertThat(started.status()).isEqualTo(200);
        assertThat(started.json().path("data").path("servicePhase").stringValue())
            .isEqualTo("waiting_station");

        ApiResult restored = request("GET", "/driver/status", null, DRIVER_ONE_TOKEN);
        assertThat(restored.status()).isEqualTo(200);
        assertThat(restored.json().path("data").path("activeBus").path("id").stringValue())
            .isEqualTo("SH-ASSIGNED");
        assertThat(restored.json().path("data").path("activeBus").path("activeTrip")
            .path("servicePhase").stringValue()).isEqualTo("waiting_station");
        assertThat(restored.json().path("data").path("activeBus").path("currentRoute")
            .path("stops").size()).isEqualTo(2);

        assertApiError(request("PUT", "/driver/progress", "{\"stopOrder\":1}", DRIVER_ONE_TOKEN),
            409, "학생 탑승 완료 후 신창역 출발을 먼저 처리해 주세요");
        assertThat(request("PUT", "/driver/phase", null, DRIVER_ONE_TOKEN).json()
            .path("data").path("servicePhase").stringValue()).isEqualTo("to_campus");

        ApiResult transitioned = request(
            "PUT", "/driver/progress", "{\"stopOrder\":2}", DRIVER_ONE_TOKEN);
        assertThat(transitioned.status()).isEqualTo(200);
        assertThat(transitioned.json().path("data").path("routeId").stringValue())
            .isEqualTo("60000000-0000-0000-0000-000000000002");
        assertThat(transitioned.json().path("data").path("oneLoopOnly").booleanValue()).isTrue();

        ApiResult restoredTransition = request("GET", "/driver/status", null, DRIVER_ONE_TOKEN);
        JsonNode activeBus = restoredTransition.json().path("data").path("activeBus");
        assertThat(activeBus.path("currentRoute").path("id").stringValue())
            .isEqualTo("60000000-0000-0000-0000-000000000002");
        assertThat(activeBus.path("activeTrip").path("originRouteId").stringValue())
            .isEqualTo("60000000-0000-0000-0000-000000000001");
    }

    @Test
    void forceStopCancelsTripRestoresOriginRouteAndClearsDriverStatus() throws Exception {
        assertThat(startAssignedBus().status()).isEqualTo(200);
        assertThat(request("PUT", "/driver/phase", null, DRIVER_ONE_TOKEN).status()).isEqualTo(200);
        assertThat(request("PUT", "/driver/progress", "{\"stopOrder\":2}", DRIVER_ONE_TOKEN).status())
            .isEqualTo(200);

        ApiResult stopped = request("POST", "/buses/SH-ASSIGNED/force-stop",
            "{\"reason\":\"통합 테스트 강제 종료\"}", ADMIN_TOKEN);
        assertThat(stopped.status()).isEqualTo(200);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM bus_trips ORDER BY started_at DESC LIMIT 1", String.class))
            .isEqualTo("cancelled");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT current_route_id::text FROM buses WHERE id = 'SH-ASSIGNED'", String.class))
            .isEqualTo("60000000-0000-0000-0000-000000000001");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT is_running FROM buses WHERE id = 'SH-ASSIGNED'", Boolean.class)).isFalse();
        assertThat(request("GET", "/driver/status", null, DRIVER_ONE_TOKEN).json()
            .path("data").path("activeBus").isNull()).isTrue();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM admin_action_logs WHERE action = 'bus_force_stopped'", Integer.class))
            .isEqualTo(1);
    }

    @Test
    void driverStopCompletesTripAndRestoresOriginRoute() throws Exception {
        assertThat(startAssignedBus().status()).isEqualTo(200);
        assertThat(request("PUT", "/driver/phase", null, DRIVER_ONE_TOKEN).status()).isEqualTo(200);
        assertThat(request("PUT", "/driver/progress", "{\"stopOrder\":2}", DRIVER_ONE_TOKEN).status())
            .isEqualTo(200);

        ApiResult stopped = request("POST", "/driver/stop", null, DRIVER_ONE_TOKEN);
        assertThat(stopped.status()).isEqualTo(200);
        assertThat(stopped.json().path("data").path("busId").stringValue()).isEqualTo("SH-ASSIGNED");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM bus_trips ORDER BY started_at DESC LIMIT 1", String.class))
            .isEqualTo("completed");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT current_route_id::text FROM buses WHERE id = 'SH-ASSIGNED'", String.class))
            .isEqualTo("60000000-0000-0000-0000-000000000001");
    }

    private ApiResult concurrentStart(CountDownLatch ready, CountDownLatch start, String token)
        throws Exception {
        return concurrentStart(ready, start, token, "SH-CONCURRENT");
    }

    private ApiResult concurrentStart(
        CountDownLatch ready,
        CountDownLatch start,
        String token,
        String busId
    ) throws Exception {
        ready.countDown();
        start.await();
        return request("POST", "/driver/start", "{\"busId\":\"" + busId + "\"}", token);
    }

    private ApiResult startAssignedBus() throws Exception {
        return request("POST", "/driver/start", "{\"busId\":\"SH-ASSIGNED\"}", DRIVER_ONE_TOKEN);
    }

    private ApiResult request(String method, String path, String body, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path));
        if (token != null) builder.header("X-Auth-Token", token);
        if (body != null) builder.header("Content-Type", "application/json");
        HttpRequest.BodyPublisher publisher = body == null
            ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body);
        HttpResponse<String> response = client.send(
            builder.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString()
        );
        return new ApiResult(response.statusCode(), objectMapper.readTree(response.body()), response.body());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private void assertApiError(ApiResult result, int status, String error) {
        assertThat(result.status()).isEqualTo(status);
        assertThat(result.json().toString())
            .isEqualTo("{\"success\":false,\"error\":" + quote(error) + "}");
    }

    private void assertRawError(ApiResult result, int status, String body) {
        assertThat(result.status()).isEqualTo(status);
        assertThat(result.body()).isEqualTo(body);
    }

    private String quote(String value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception error) { throw new AssertionError(error); }
    }

    private record ApiResult(int status, JsonNode json, String body) {
    }
}
