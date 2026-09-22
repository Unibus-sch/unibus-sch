package com.unibus.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "app.schema.validation.enabled=false"
)
class BackendApplicationIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgres:15-alpine"));

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.cors.allowed-origins", () ->
            "http://localhost:5173,https://unibus-sch.vercel.app");
        registry.add("app.cors.allowed-origin-patterns", () ->
            "https://unibus-sch-git-*-ddingddong9s-projects.vercel.app");
    }

    @LocalServerPort
    int port;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Test
    void startsWithPostgresAndReportsHealthy() throws Exception {
        HttpResponse<String> response = send("/actuator/health", null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }

    @Test
    void exposesCompatibilityHealthEnvelopeAndCorsForLocalFrontend() throws Exception {
        HttpResponse<String> response = send("/health", "http://localhost:5173");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("{\"success\":true,\"data\":{\"status\":\"UP\"}}");
        assertThat(response.headers().firstValue("access-control-allow-origin"))
            .contains("http://localhost:5173");
    }

    @Test
    void allowsProductionAndScopedPreviewOriginsButRejectsUnknownOrigins() throws Exception {
        HttpResponse<String> production = preflight("https://unibus-sch.vercel.app");
        assertThat(production.statusCode()).isEqualTo(200);
        assertThat(production.headers().firstValue("access-control-allow-origin"))
            .contains("https://unibus-sch.vercel.app");

        String previewOrigin =
            "https://unibus-sch-git-feature-ddingddong9s-projects.vercel.app";
        HttpResponse<String> preview = preflight(previewOrigin);
        assertThat(preview.statusCode()).isEqualTo(200);
        assertThat(preview.headers().firstValue("access-control-allow-origin"))
            .contains(previewOrigin);

        HttpResponse<String> unknown = preflight("https://attacker.example");
        assertThat(unknown.statusCode()).isEqualTo(403);
        assertThat(unknown.headers().firstValue("access-control-allow-origin")).isEmpty();
    }

    @Test
    void matchesEdgeNotFoundAndAuthenticatedCacheHeaders() throws Exception {
        HttpResponse<String> missing = send("/does-not-exist", null);
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(missing.body()).isEqualTo("{\"error\":\"Not Found\"}");

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + "/health"))
            .header("X-Auth-Token", "test-token")
            .GET()
            .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.headers().firstValue("cache-control"))
            .contains("private, no-store");
        assertThat(response.headers().allValues("vary"))
            .anySatisfy(value -> assertThat(value).containsIgnoringCase("X-Auth-Token"));
        assertThat(response.headers().firstValue("x-content-type-options")).contains("nosniff");
    }

    @Test
    void protectsMigratedAdminRoutes() throws Exception {
        HttpResponse<String> response = send("/users", null);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).isEqualTo("{\"error\":\"Unauthorized: No token provided\"}");
    }

    private HttpResponse<String> send(String path, String origin) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + path))
            .GET();
        if (origin != null) {
            request.header("Origin", origin);
        }
        return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> preflight(String origin) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + "/notices"))
            .header("Origin", origin)
            .header("Access-Control-Request-Method", "GET")
            .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
            .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
