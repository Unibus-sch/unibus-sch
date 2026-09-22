package com.unibus.backend.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
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
@Sql("/sql/admin-api-schema.sql")
class AdminApiIntegrationTest {

    private static final String ADMIN_TOKEN = "admin-session-token";
    private static final String USER_TOKEN = "user-session-token";
    private static final String EXPIRED_TOKEN = "expired-admin-token";
    private static final String TEST_SERVICE_ROLE_KEY = "isolated-test-service-role";
    private static final AtomicReference<StorageRequest> STORAGE_REQUEST = new AtomicReference<>();
    private static final HttpServer STORAGE_SERVER = startStorageServer();

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
        DockerImageName.parse("postgres:15-alpine"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.supabase.api-url", () -> "http://127.0.0.1:" + STORAGE_SERVER.getAddress().getPort());
        registry.add("app.supabase.service-role-key", () -> TEST_SERVICE_ROLE_KEY);
        registry.add("app.push.vapid-public-key", () -> "test-public-vapid-key");
        registry.add("app.push.allowed-hosts", () -> "push.example.test");
    }

    @AfterAll
    static void stopStorageServer() {
        STORAGE_SERVER.stop(0);
    }

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void rejectsMissingInvalidExpiredAndNonAdminSessionsWithEdgeCompatibleErrors() throws Exception {
        assertError(request("GET", "/users", null, null), 401,
            "{\"error\":\"Unauthorized: No token provided\"}");
        assertError(request("GET", "/users", null, "invalid-session-token"), 401,
            "{\"error\":\"Unauthorized: Invalid token\"}");
        assertError(request("GET", "/users", null, EXPIRED_TOKEN), 401,
            "{\"error\":\"Unauthorized: Token expired\"}");
        assertError(request("GET", "/users", null, USER_TOKEN), 403,
            "{\"error\":\"Forbidden: Admin access required\"}");
        assertError(request("GET", "/users/", null, USER_TOKEN), 403,
            "{\"error\":\"Forbidden: Admin access required\"}");
        assertError(request("POST", "/notices", "{\"title\":\"x\",\"content\":\"y\"}", USER_TOKEN), 403,
            "{\"error\":\"Forbidden: Admin access required\"}");

        ApiResult allowed = request("GET", "/users", null, ADMIN_TOKEN);
        assertThat(allowed.status()).isEqualTo(200);
        assertThat(allowed.json().path("data").size()).isEqualTo(3);
    }

    @Test
    void validatesNoticeInputAndCreatesNoticeForAdmin() throws Exception {
        ApiResult invalid = request("POST", "/notices", "{\"title\":\" \",\"content\":\"내용\"}", ADMIN_TOKEN);
        assertApiError(invalid, 400, "Missing required fields");

        ApiResult created = request("POST", "/notices", """
            {"title":" 공지 ","content":" 내용 ","category":"general","priority":"high","imageUrls":[]}
            """, ADMIN_TOKEN);
        assertThat(created.status()).isEqualTo(200);
        assertThat(created.json().path("data").path("title").stringValue()).isEqualTo("공지");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notices", Integer.class)).isEqualTo(1);

        String id = created.json().path("data").path("id").stringValue();
        ApiResult updated = request("PUT", "/notices/" + id,
            "{\"title\":\"수정 공지\",\"imageUrls\":[]}", ADMIN_TOKEN);
        assertThat(updated.status()).isEqualTo(200);
        assertThat(updated.json().path("data").path("title").stringValue()).isEqualTo("수정 공지");
    }

    @Test
    void validatesRouteStopsAndServiceSchedule() throws Exception {
        ApiResult duplicate = request("POST", "/routes", """
            {"name":"셔틀","type":"campus","shuttleVariant":"campus_to_station",
             "schedule":"08:20","stops":[{"name":"신창역"},{"name":"신창역"}]}
            """, ADMIN_TOKEN);
        assertThat(duplicate.status()).isEqualTo(400);
        assertThat(duplicate.json().path("error").stringValue()).contains("중복된 정류장");

        ApiResult badTime = request("POST", "/routes", """
            {"name":"셔틀","type":"campus","shuttleVariant":"campus_to_station",
             "schedule":"25:99","stops":[{"name":"학교"},{"name":"신창역"}]}
            """, ADMIN_TOKEN);
        assertThat(badTime.status()).isEqualTo(400);
        assertThat(badTime.json().path("error").stringValue()).contains("24시간 형식");

        ApiResult created = request("POST", "/routes", """
            {"name":"순환 셔틀","type":"campus","shuttleVariant":"campus_loop",
             "stops":[{"name":"학교","order":1},{"name":"기숙사","order":2}]}
            """, ADMIN_TOKEN);
        assertThat(created.status()).isEqualTo(200);
        assertThat(created.json().path("data").path("type").stringValue()).isEqualTo("campus");
    }

    @Test
    void validatesBusCapacityDriverAndActiveDeleteConflict() throws Exception {
        assertApiError(request("POST", "/buses", """
            {"name":"버스","type":"commuter","capacity":101}
            """, ADMIN_TOKEN), 400, "Capacity must be an integer between 1 and 100");

        ApiResult created = request("POST", "/buses", """
            {"name":"신규 버스","type":"commuter","capacity":40,"licensePlate":"34나5678"}
            """, ADMIN_TOKEN);
        assertThat(created.status()).isEqualTo(200);
        assertThat(created.json().path("data").path("id").stringValue()).startsWith("CM-");

        assertApiError(request("PUT", "/buses/CM-TEST0001", """
            {"assignedDriverId":"50000000-0000-0000-0000-000000000002"}
            """, ADMIN_TOKEN), 400, "선택한 사용자는 버스 기사가 아닙니다");

        jdbcTemplate.update("UPDATE buses SET status = 'active' WHERE id = 'CM-TEST0001'");
        assertApiError(request("DELETE", "/buses/CM-TEST0001", null, ADMIN_TOKEN), 409,
            "Cannot delete an active bus. Stop the bus first.");
    }

    @Test
    void managedBusReadIncludesAdminOnlyFields() throws Exception {
        ApiResult publicRead = request("GET", "/buses", null, null);
        assertThat(publicRead.status()).isEqualTo(200);
        assertThat(publicRead.json().path("data").path(0).has("licensePlate")).isFalse();

        ApiResult adminRead = request("GET", "/buses", null, ADMIN_TOKEN);
        assertThat(adminRead.status()).isEqualTo(200);
        assertThat(adminRead.json().path("data").path(0).path("licensePlate").stringValue())
            .isEqualTo("12가3456");
    }

    @Test
    void validatesUserRoleChangesIncludingSelfDemotion() throws Exception {
        assertApiError(request("PUT", "/users/50000000-0000-0000-0000-000000000001/role",
            "{\"role\":\"user\"}", ADMIN_TOKEN), 400, "자신의 역할은 변경할 수 없습니다");
        assertApiError(request("PUT", "/users/50000000-0000-0000-0000-000000000002/role",
            "{\"role\":\"owner\"}", ADMIN_TOKEN), 400,
            "Invalid role. Must be user, admin, or driver");
    }

    @Test
    void validatesReportStatusAndAdminNoteLengthThenUpdates() throws Exception {
        ApiResult unfiltered = request("GET", "/reports", null, ADMIN_TOKEN);
        assertThat(unfiltered.status()).isEqualTo(200);
        assertThat(unfiltered.json().path("data").size()).isEqualTo(1);

        ApiResult unknownFilter = request("GET", "/reports?status=unknown", null, ADMIN_TOKEN);
        assertThat(unknownFilter.status()).isEqualTo(200);
        assertThat(unknownFilter.json().path("data").size()).isEqualTo(1);

        String path = "/reports/70000000-0000-0000-0000-000000000001";
        assertApiError(request("PUT", path, "{\"status\":\"closed\"}", ADMIN_TOKEN), 400,
            "올바르지 않은 처리 상태입니다");
        String longNote = "가".repeat(5_001);
        assertApiError(request("PUT", path, objectMapper.writeValueAsString(
            java.util.Map.of("adminNote", longNote)), ADMIN_TOKEN), 400, "관리자 메모가 너무 깁니다");

        ApiResult updated = request("PUT", path,
            "{\"status\":\"resolved\",\"adminNote\":\"처리 완료\"}", ADMIN_TOKEN);
        assertThat(updated.status()).isEqualTo(200);
        assertThat(updated.json().path("data").path("status").stringValue()).isEqualTo("resolved");
        assertThat(updated.json().path("data").path("resolvedAt").isNull()).isFalse();
    }

    @Test
    void validatesNotificationInputAndRecordsDeliveryWithoutVapidKeys() throws Exception {
        assertApiError(request("POST", "/notifications/send", """
            {"title":"제목","message":"내용","target":"unknown"}
            """, ADMIN_TOKEN), 400, "Invalid notification target");

        ApiResult sent = request("POST", "/notifications/send", """
            {"title":"공지 알림","message":"알림 내용","target":"all"}
            """, ADMIN_TOKEN);
        assertThat(sent.status()).isEqualTo(200);
        assertThat(sent.json().path("data").path("push").path("attempted").asInt()).isZero();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM notification_deliveries", Integer.class)).isEqualTo(1);
    }

    @Test
    void migratesPushSubscriptionLifecycleWithSessionCompatibility() throws Exception {
        ApiResult publicKey = request("GET", "/notifications/vapid-public-key", null, null);
        assertThat(publicKey.status()).isEqualTo(200);
        assertThat(publicKey.json().path("data").path("publicKey").stringValue())
            .isEqualTo("test-public-vapid-key");

        assertError(request("POST", "/notifications/subscribe", "{}", null), 401,
            "{\"error\":\"Unauthorized: No token provided\"}");
        assertError(request("POST", "/notifications/subscribe", "{}", EXPIRED_TOKEN), 401,
            "{\"error\":\"Unauthorized: Token expired\"}");
        assertApiError(request("POST", "/notifications/subscribe", "{}", USER_TOKEN), 400,
            "Invalid push subscription");

        String subscription = """
            {"subscription":{"endpoint":"https://push.example.test/subscription/one",
             "keys":{"p256dh":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                     "auth":"BBBBBBBBBBBBBBBB"}}}
            """;
        ApiResult subscribed = request("POST", "/notifications/subscribe", subscription, USER_TOKEN);
        assertThat(subscribed.status()).isEqualTo(200);
        assertThat(subscribed.json().path("data").path("id").isString()).isTrue();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT enabled FROM push_subscriptions", Boolean.class)).isTrue();

        assertApiError(request("POST", "/notifications/subscribe", subscription, ADMIN_TOKEN), 409,
            "This push subscription belongs to another account");
        ApiResult unsubscribed = request("POST", "/notifications/unsubscribe", """
            {"endpoint":"https://push.example.test/subscription/one"}
            """, USER_TOKEN);
        assertThat(unsubscribed.status()).isEqualTo(200);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT enabled FROM push_subscriptions", Boolean.class)).isFalse();
    }

    @Test
    void rejectsMismatchedImageSignatureBeforeStorageCall() throws Exception {
        String boundary = "----unibus-test-boundary";
        String multipart = "--" + boundary + "\r\n"
            + "Content-Disposition: form-data; name=\"file\"; filename=\"fake.png\"\r\n"
            + "Content-Type: image/png\r\n\r\nnot-a-png\r\n"
            + "--" + boundary + "--\r\n";
        HttpRequest request = HttpRequest.newBuilder(uri("/notices/images"))
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .header("X-Auth-Token", ADMIN_TOKEN)
            .POST(HttpRequest.BodyPublishers.ofString(multipart))
            .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(objectMapper.readTree(response.body()).path("error").stringValue())
            .isEqualTo("파일 형식과 실제 이미지 내용이 일치하지 않습니다");
    }

    @Test
    void uploadsValidatedImageThroughSupabaseStorageHttpBoundary() throws Exception {
        STORAGE_REQUEST.set(null);
        String boundary = "----unibus-storage-boundary";
        byte[] png = new byte[] {
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
            0x00, 0x00, 0x00, 0x00
        };
        byte[] multipart = multipart(boundary, "pixel.png", "image/png", png);
        HttpRequest upload = HttpRequest.newBuilder(uri("/notices/images"))
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .header("X-Auth-Token", ADMIN_TOKEN)
            .POST(HttpRequest.BodyPublishers.ofByteArray(multipart))
            .build();
        HttpResponse<String> response = client.send(upload, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode json = objectMapper.readTree(response.body());
        assertThat(json.path("data").path("url").stringValue())
            .startsWith("http://127.0.0.1:" + STORAGE_SERVER.getAddress().getPort()
                + "/storage/v1/object/public/notice-images/");
        StorageRequest captured = STORAGE_REQUEST.get();
        assertThat(captured).isNotNull();
        assertThat(captured.path()).startsWith("/storage/v1/object/notice-images/");
        assertThat(captured.authorization()).isEqualTo("Bearer " + TEST_SERVICE_ROLE_KEY);
        assertThat(captured.apiKey()).isEqualTo(TEST_SERVICE_ROLE_KEY);
        assertThat(captured.contentType()).isEqualTo("image/png");
        assertThat(captured.body()).containsExactly(png);
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

    private void assertError(ApiResult result, int status, String body) {
        assertThat(result.status()).isEqualTo(status);
        assertThat(result.body()).isEqualTo(body);
    }

    private String quote(String value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception error) { throw new AssertionError(error); }
    }

    private static HttpServer startStorageServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/storage/v1/object/notice-images/", AdminApiIntegrationTest::captureStorage);
            server.start();
            return server;
        } catch (IOException error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    private static void captureStorage(HttpExchange exchange) throws IOException {
        STORAGE_REQUEST.set(new StorageRequest(
            exchange.getRequestURI().getPath(),
            exchange.getRequestHeaders().getFirst("Authorization"),
            exchange.getRequestHeaders().getFirst("apikey"),
            exchange.getRequestHeaders().getFirst("Content-Type"),
            exchange.getRequestBody().readAllBytes()
        ));
        byte[] response = "{\"Key\":\"notice-images/test.png\"}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private static byte[] multipart(
        String boundary,
        String filename,
        String contentType,
        byte[] content
    ) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(("--" + boundary + "\r\n"
            + "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
            + "Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(content);
        output.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return output.toByteArray();
    }

    private record ApiResult(int status, JsonNode json, String body) {
    }

    private record StorageRequest(
        String path,
        String authorization,
        String apiKey,
        String contentType,
        byte[] body
    ) {
    }
}
