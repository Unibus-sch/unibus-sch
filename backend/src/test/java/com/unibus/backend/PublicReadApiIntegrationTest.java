package com.unibus.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

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
@Sql("/sql/public-api-schema.sql")
class PublicReadApiIntegrationTest {

    private static final String NOTICE_ID = "10000000-0000-0000-0000-000000000001";
    private static final String ROUTE_ID = "20000000-0000-0000-0000-000000000001";
    private static final String EMPTY_ROUTE_ID = "20000000-0000-0000-0000-000000000002";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgres:15-alpine"));

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.naver.client-id", () -> "");
        registry.add("app.naver.secret-key", () -> "");
    }

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Test
    void noticesMatchEdgeStatusFieldsAndNullNormalization() throws Exception {
        ApiResult list = get("/notices");
        assertThat(list.status()).isEqualTo(200);
        JsonNode notice = list.json().path("data").path(0);
        assertThat(notice.path("id").stringValue()).isEqualTo(NOTICE_ID);
        assertThat(notice.path("isPinned").asBoolean()).isTrue();
        assertThat(notice.path("imageUrls").isArray()).isTrue();
        assertThat(notice.path("imageUrls").isEmpty()).isTrue();
        assertThat(notice.path("contentBelow").stringValue()).isEmpty();

        ApiResult detail = get("/notices/" + NOTICE_ID);
        assertThat(detail.status()).isEqualTo(200);
        assertThat(detail.json().path("data").path("viewCount").asInt()).isEqualTo(8);

        ApiResult missing = get("/notices/not-a-uuid");
        assertThat(missing.status()).isEqualTo(404);
        assertThat(missing.json().toString())
            .isEqualTo("{\"success\":false,\"error\":\"Notice not found\"}");
    }

    @Test
    void routesMatchEdgeNullEmptyArrayAndPathContracts() throws Exception {
        ApiResult list = get("/routes");
        assertThat(list.status()).isEqualTo(200);
        JsonNode campusRoute = list.json().path("data").path(1);
        assertThat(campusRoute.path("id").stringValue()).isEqualTo(ROUTE_ID);
        assertThat(campusRoute.path("type").stringValue()).isEqualTo("campus");
        assertThat(campusRoute.has("description")).isTrue();
        assertThat(campusRoute.path("description").isNull()).isTrue();
        assertThat(campusRoute.path("stops").size()).isEqualTo(2);
        assertThat(campusRoute.path("shapePoints").path(0).path("name").isNull()).isTrue();
        assertThat(campusRoute.path("stops").path(1).path("arrivalTime").isNull()).isTrue();
        assertThat(campusRoute.path("createdAt").stringValue())
            .isEqualTo("2026-07-03T01:02:03+00:00");

        ApiResult path = get("/routes/" + ROUTE_ID + "/path");
        assertThat(path.status()).isEqualTo(200);
        assertThat(path.json().path("data").path("cached").asBoolean()).isFalse();
        assertThat(path.json().path("data").path("path").size()).isEqualTo(3);
        assertThat(path.json().path("data").path("path").path(0).path(0).asDouble())
            .isEqualTo(126.93);
        assertThat(path.json().path("data").path("stops").path(0).has("arrivalTime")).isFalse();

        ApiResult noStops = get("/routes/" + EMPTY_ROUTE_ID + "/path");
        assertThat(noStops.status()).isEqualTo(404);
        assertThat(noStops.json().toString())
            .isEqualTo("{\"success\":false,\"error\":\"No stops found\"}");

        ApiResult missing = get("/routes/not-a-uuid");
        assertThat(missing.status()).isEqualTo(404);
        assertThat(missing.json().toString())
            .isEqualTo("{\"success\":false,\"error\":\"Route not found\"}");

        jdbcTemplate.update("""
            INSERT INTO routes (id, name, type, is_active)
            VALUES ('20000000-0000-0000-0000-000000000003', '좌표 미상', 'shuttle', true)
            """);
        jdbcTemplate.update("""
            INSERT INTO route_stops (id, route_id, stop_name, stop_order)
            VALUES (
                '21000000-0000-0000-0000-000000000003',
                '20000000-0000-0000-0000-000000000003',
                '미확인 정류장',
                1
            )
            """);
        for (int request = 0; request < 10; request++) {
            assertThat(get("/routes/20000000-0000-0000-0000-000000000003/path").status())
                .isEqualTo(200);
        }
        ApiResult limited = get("/routes/20000000-0000-0000-0000-000000000003/path");
        assertThat(limited.status()).isEqualTo(429);
        assertThat(limited.retryAfter()).isEqualTo("3600");
        assertThat(limited.json().toString()).isEqualTo(
            "{\"success\":false,\"error\":\"요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.\"}"
        );
    }

    @Test
    void busesMatchEdgeRedactionAndNullableNestedObjects() throws Exception {
        ApiResult list = get("/buses");
        assertThat(list.status()).isEqualTo(200);
        JsonNode running = list.json().path("data").path(0);
        assertThat(running.path("type").stringValue()).isEqualTo("commuter");
        assertThat(running.path("currentRoute").path("id").stringValue()).isEqualTo(ROUTE_ID);
        assertThat(running.path("activeTrip").path("servicePhase").stringValue()).isEqualTo("in_service");
        assertThat(running.path("location").path("lat").asDouble()).isEqualTo(36.771);
        assertThat(running.has("licensePlate")).isFalse();

        JsonNode idle = list.json().path("data").path(1);
        assertThat(idle.path("type").stringValue()).isEqualTo("campus");
        assertThat(idle.path("currentRoute").isNull()).isTrue();
        assertThat(idle.path("activeTrip").isNull()).isTrue();
        assertThat(idle.path("location").isNull()).isTrue();
        assertThat(idle.path("lastLocationAt").isNull()).isTrue();

        ApiResult detail = get("/buses/CM-001");
        assertThat(detail.status()).isEqualTo(200);
        assertThat(detail.json().path("data").path("location").path("heading").asInt()).isEqualTo(88);
        assertThat(detail.json().path("data").has("activeTrip")).isFalse();
        assertThat(detail.json().path("data").has("lastLocationAt")).isFalse();

        ApiResult locations = get("/buses/locations/latest");
        assertThat(locations.status()).isEqualTo(200);
        assertThat(locations.json().path("data").path(0).path("busId").stringValue()).isEqualTo("CM-001");

        ApiResult missing = get("/buses/UNKNOWN");
        assertThat(missing.status()).isEqualTo(404);
        assertThat(missing.json().toString())
            .isEqualTo("{\"success\":false,\"error\":\"Bus not found\"}");
    }

    @Test
    void collectionEndpointsReturnEmptyArraysInsteadOfNull() throws Exception {
        jdbcTemplate.update("DELETE FROM bus_latest_state");
        jdbcTemplate.update("DELETE FROM bus_locations");
        jdbcTemplate.update("DELETE FROM bus_trips");
        jdbcTemplate.update("DELETE FROM buses");
        jdbcTemplate.update("DELETE FROM route_path_cache");
        jdbcTemplate.update("DELETE FROM route_shape_points");
        jdbcTemplate.update("DELETE FROM route_stops");
        jdbcTemplate.update("DELETE FROM notices");
        jdbcTemplate.update("DELETE FROM routes");

        assertThat(get("/notices").json().path("data").isEmpty()).isTrue();
        assertThat(get("/routes").json().path("data").isEmpty()).isTrue();
        assertThat(get("/buses").json().path("data").isEmpty()).isTrue();
        assertThat(get("/buses/locations/latest").json().path("data").isEmpty()).isTrue();
    }

    private ApiResult get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + path))
            .GET()
            .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return new ApiResult(
            response.statusCode(),
            objectMapper.readTree(response.body()),
            response.headers().firstValue("Retry-After").orElse(null)
        );
    }

    private record ApiResult(int status, JsonNode json, String retryAfter) {
    }
}
