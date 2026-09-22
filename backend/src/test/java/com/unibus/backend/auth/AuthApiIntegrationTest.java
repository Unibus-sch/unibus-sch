package com.unibus.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
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
@Sql("/sql/auth-api-schema.sql")
class AuthApiIntegrationTest {

    private static final String EDGE_RAW_TOKEN = "edge-issued-session-token_1234567890";
    private static final String EDGE_HASHED_TOKEN =
        "sha256:98LYBMfn0abs1M6zx6IJwUpA7m3fRKMlF3JJNKPenhU";
    private static final String VALID_KAKAO_TOKEN = "valid-kakao-access-token-for-local-test";
    private static final String PROFILELESS_KAKAO_TOKEN = "profileless-kakao-token-for-local-test";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
        DockerImageName.parse("postgres:15-alpine"));

    private static final HttpServer KAKAO_SERVER = createKakaoServer();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.kakao.user-info-url", () ->
            "http://127.0.0.1:" + KAKAO_SERVER.getAddress().getPort() + "/v2/user/me");
    }

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    SessionTokenCodec tokenCodec;

    @Autowired
    SessionService sessionService;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @AfterAll
    static void stopKakaoServer() {
        KAKAO_SERVER.stop(0);
    }

    @Test
    void loginAcceptsExistingBcryptJsAccountAndIssuesEdgeCompatibleSession() throws Exception {
        ApiResult login = post("/auth/login", """
            {"email":"  LEGACY@EXAMPLE.COM ","password":"P@ssw0rd!기존"}
            """, null);

        assertThat(login.status()).isEqualTo(200);
        assertThat(login.json().path("success").asBoolean()).isTrue();
        assertThat(login.json().path("user").path("id").stringValue())
            .isEqualTo("40000000-0000-0000-0000-000000000001");
        assertThat(login.json().path("user").has("studentId")).isTrue();
        assertThat(login.json().path("user").path("studentId").isNull()).isTrue();
        assertThat(login.json().path("user").has("provider")).isFalse();

        String rawToken = login.json().path("token").stringValue();
        assertThat(rawToken).matches("[A-Za-z0-9_-]{43}");
        String storedToken = jdbcTemplate.queryForObject(
            "SELECT token FROM auth_tokens WHERE token = ?",
            String.class,
            tokenCodec.hash(rawToken)
        );
        assertThat(storedToken).isEqualTo(tokenCodec.hash(rawToken)).startsWith("sha256:");
        assertThat(storedToken).isNotEqualTo(rawToken);
        assertThat(sessionService.validate(rawToken).status())
            .isEqualTo(SessionService.SessionValidation.Status.VALID);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM api_rate_limits",
            Integer.class
        )).isEqualTo(1);

        ApiResult wrongPassword = post("/auth/login", """
            {"email":"legacy@example.com","password":"wrong-password"}
            """, null);
        assertThat(wrongPassword.status()).isEqualTo(401);
        assertThat(wrongPassword.json().toString())
            .isEqualTo("{\"success\":false,\"error\":\"Invalid credentials\"}");
    }

    @Test
    void signupPreservesValidationNormalizationAndBcryptContract() throws Exception {
        ApiResult signup = post("/auth/signup", """
            {
              "email":"  NEW.USER@Example.COM ",
              "password":"new-password",
              "name":"  신규 사용자  ",
              "studentId":"  20260001  "
            }
            """, null);

        assertThat(signup.status()).isEqualTo(200);
        JsonNode user = signup.json().path("data").path("user");
        assertThat(user.path("email").stringValue()).isEqualTo("new.user@example.com");
        assertThat(user.path("name").stringValue()).isEqualTo("신규 사용자");
        assertThat(user.path("studentId").stringValue()).isEqualTo("20260001");
        String passwordHash = jdbcTemplate.queryForObject(
            "SELECT password_hash FROM users WHERE email = 'new.user@example.com'",
            String.class
        );
        assertThat(passwordHash).startsWith("$2b$10$");
        assertThat(passwordEncoder.matches("new-password", passwordHash)).isTrue();

        ApiResult duplicate = post("/auth/signup", """
            {"email":"new.user@example.com","password":"new-password","name":"중복"}
            """, null);
        assertThat(duplicate.status()).isEqualTo(400);
        assertThat(duplicate.json().toString())
            .isEqualTo("{\"success\":false,\"error\":\"User already exists\"}");
    }

    @Test
    void acceptsHashedEdgeSessionsAndUpgradesLegacyPlaintextSessions() {
        assertThat(sessionService.validate(EDGE_RAW_TOKEN).status())
            .isEqualTo(SessionService.SessionValidation.Status.VALID);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM auth_tokens WHERE token = ?",
            Integer.class,
            EDGE_HASHED_TOKEN
        )).isEqualTo(1);

        assertThat(sessionService.validate("legacy-plaintext-session-token").status())
            .isEqualTo(SessionService.SessionValidation.Status.VALID);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM auth_tokens WHERE token = 'legacy-plaintext-session-token'",
            Integer.class
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM auth_tokens WHERE token = ?",
            Integer.class,
            "sha256:8d7E8fJEz3fqQhsf07iG9VLVOsCySn94R17fuJWEJdk"
        )).isEqualTo(1);

        assertThat(sessionService.validate("expired-edge-session-token").status())
            .isEqualTo(SessionService.SessionValidation.Status.EXPIRED);
    }

    @Test
    void logoutDeletesBothCurrentHashedAndLegacyPlaintextTokenRecords() throws Exception {
        ApiResult hashedLogout = post("/auth/logout", "", EDGE_RAW_TOKEN);
        assertThat(hashedLogout.status()).isEqualTo(200);
        assertThat(hashedLogout.json().toString())
            .isEqualTo("{\"success\":true,\"message\":\"Logged out successfully\"}");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM auth_tokens WHERE token = ?",
            Integer.class,
            EDGE_HASHED_TOKEN
        )).isZero();

        ApiResult legacyLogout = post("/auth/logout", "", "legacy-plaintext-session-token");
        assertThat(legacyLogout.status()).isEqualTo(200);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM auth_tokens WHERE token = 'legacy-plaintext-session-token'",
            Integer.class
        )).isZero();
    }

    @Test
    void preservesCompatibilityHeadersWhenAuthenticationCommitsEarly() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + "/notifications/subscribe"))
            .header("X-Auth-Token", "invalid-token")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{}"))
            .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("cache-control"))
            .contains("private, no-store");
        assertThat(response.headers().allValues("vary"))
            .anySatisfy(value -> assertThat(value).containsIgnoringCase("X-Auth-Token"));
        assertThat(response.headers().firstValue("x-content-type-options")).contains("nosniff");
    }

    @Test
    void kakaoLoginUsesTheVerifiedRemoteProfileAndIssuesCompatibleSession() throws Exception {
        ApiResult login = post("/auth/kakao", """
            {
              "kakaoId":"spoofed-client-id",
              "accessToken":"valid-kakao-access-token-for-local-test",
              "email":"spoofed@example.com",
              "name":"Spoofed"
            }
            """, null);

        assertThat(login.status()).isEqualTo(200);
        JsonNode user = login.json().path("user");
        assertThat(user.path("email").stringValue()).isEqualTo("verified@kakao.test");
        assertThat(user.path("name").stringValue()).isEqualTo("검증된 카카오 사용자");
        assertThat(user.path("provider").stringValue()).isEqualTo("kakao");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT provider_id FROM users WHERE email = 'verified@kakao.test'",
            String.class
        )).isEqualTo("987654321");
        String rawToken = login.json().path("token").stringValue();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM auth_tokens WHERE token = ?",
            Integer.class,
            tokenCodec.hash(rawToken)
        )).isEqualTo(1);

        ApiResult invalid = post("/auth/kakao", """
            {"accessToken":"invalid-kakao-access-token-for-test"}
            """, null);
        assertThat(invalid.status()).isEqualTo(401);
        assertThat(invalid.json().toString())
            .isEqualTo("{\"success\":false,\"error\":\"Invalid Kakao token\"}");

        ApiResult profileless = post("/auth/kakao", """
            {"accessToken":"profileless-kakao-token-for-local-test"}
            """, null);
        assertThat(profileless.status()).isEqualTo(401);
        assertThat(profileless.json().toString())
            .isEqualTo("{\"success\":false,\"error\":\"Invalid Kakao profile\"}");
    }

    @Test
    void loginRateLimitMatchesTheSharedEdgeContract() throws Exception {
        for (int request = 0; request < 10; request++) {
            ApiResult invalid = post("/auth/login", """
                {"email":"missing@example.com","password":"wrong-password"}
                """, null);
            assertThat(invalid.status()).isEqualTo(401);
        }

        ApiResult limited = post("/auth/login", """
            {"email":"missing@example.com","password":"wrong-password"}
            """, null);
        assertThat(limited.status()).isEqualTo(429);
        assertThat(limited.retryAfter()).isEqualTo("900");
        assertThat(limited.json().toString()).isEqualTo(
            "{\"success\":false,\"error\":\"요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.\"}"
        );
    }

    private ApiResult post(String path, String body, String authToken) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + path))
            .header("Content-Type", "application/json");
        if (authToken != null) {
            request.header("X-Auth-Token", authToken);
        }
        HttpResponse<String> response = httpClient.send(
            request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString()
        );
        return new ApiResult(
            response.statusCode(),
            objectMapper.readTree(response.body()),
            response.headers().firstValue("Retry-After").orElse(null)
        );
    }

    private static HttpServer createKakaoServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v2/user/me", AuthApiIntegrationTest::handleKakaoProfile);
            server.start();
            return server;
        } catch (IOException error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    private static void handleKakaoProfile(HttpExchange exchange) throws IOException {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        if (("Bearer " + PROFILELESS_KAKAO_TOKEN).equals(authorization)) {
            byte[] response = "{\"kakao_account\":{}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
            return;
        }
        if (!("Bearer " + VALID_KAKAO_TOKEN).equals(authorization)) {
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
            return;
        }
        byte[] response = """
            {
              "id": 987654321,
              "kakao_account": {
                "email": "verified@kakao.test",
                "profile": {
                  "nickname": "검증된 카카오 사용자",
                  "profile_image_url": "https://example.test/profile.png"
                }
              }
            }
            """.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private record ApiResult(int status, JsonNode json, String retryAfter) {
    }
}
