package com.unibus.backend.route;

import java.util.Map;

import com.unibus.backend.common.api.ApiResponse;
import com.unibus.backend.common.web.RateLimitExceededException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.unibus.backend.common.api.ApiRequestException;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/routes")
public class RouteController {

    private static final Logger log = LoggerFactory.getLogger(RouteController.class);
    private final RouteService routeService;
    private final RouteAdminService routeAdminService;

    public RouteController(RouteService routeService, RouteAdminService routeAdminService) {
        this.routeService = routeService;
        this.routeAdminService = routeAdminService;
    }

    @PostMapping
    ResponseEntity<?> create(@RequestBody(required = false) JsonNode body) {
        try {
            return ResponseEntity.ok(ApiResponse.success(routeAdminService.create(body)));
        } catch (ApiRequestException error) {
            return ResponseEntity.status(error.status()).body(ApiResponse.error(error.getMessage()));
        } catch (RuntimeException error) {
            log.error("Failed to create route", error);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to create route"));
        }
    }

    @PutMapping("/{id}")
    ResponseEntity<?> update(@PathVariable String id, @RequestBody(required = false) JsonNode body) {
        try {
            return ResponseEntity.ok(ApiResponse.success(routeAdminService.update(id, body)));
        } catch (ApiRequestException error) {
            return ResponseEntity.status(error.status()).body(ApiResponse.error(error.getMessage()));
        } catch (RuntimeException error) {
            log.error("Failed to update route", error);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to update route"));
        }
    }

    @DeleteMapping("/{id}")
    ResponseEntity<?> delete(@PathVariable String id) {
        try {
            routeAdminService.delete(id);
            return ResponseEntity.ok(new DeleteResponse(true, "Route deleted successfully"));
        } catch (ApiRequestException error) {
            return ResponseEntity.status(error.status()).body(ApiResponse.error(error.getMessage()));
        } catch (RuntimeException error) {
            log.error("Failed to delete route", error);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to delete route"));
        }
    }

    @PostMapping("/{id}/path/preview")
    ResponseEntity<?> preview(@PathVariable String id, @RequestBody(required = false) JsonNode body) {
        try {
            return ResponseEntity.ok(ApiResponse.success(routeAdminService.preview(body)));
        } catch (RuntimeException error) {
            log.error("Failed to preview route path", error);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to preview route path"));
        }
    }

    private record DeleteResponse(boolean success, String message) {
    }

    @GetMapping
    ResponseEntity<ApiResponse<?>> findAll() {
        try {
            return ResponseEntity.ok(ApiResponse.success(routeService.findAll()));
        } catch (RuntimeException error) {
            log.error("Failed to fetch routes", error);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to fetch routes"));
        }
    }

    @GetMapping("/{id}")
    ResponseEntity<ApiResponse<?>> findById(@PathVariable String id) {
        try {
            return routeService.findById(id)
                .<ResponseEntity<ApiResponse<?>>>map(route -> ResponseEntity.ok(ApiResponse.success(route)))
                .orElseGet(() -> ResponseEntity.status(404).body(ApiResponse.error("Route not found")));
        } catch (RuntimeException error) {
            log.error("Failed to fetch route", error);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to fetch route"));
        }
    }

    @GetMapping("/{id}/path")
    ResponseEntity<ApiResponse<?>> findPath(
        @PathVariable String id,
        HttpServletRequest request
    ) {
        try {
            String identity = requestIdentity(request);
            return routeService.findPath(id, identity)
                .<ResponseEntity<ApiResponse<?>>>map(path -> ResponseEntity.ok(ApiResponse.success(path)))
                .orElseGet(() -> ResponseEntity.status(404).body(ApiResponse.error("No stops found")));
        } catch (RateLimitExceededException error) {
            return ResponseEntity.status(429)
                .header("Retry-After", String.valueOf(error.retryAfterSeconds()))
                .body(ApiResponse.error("요청이 너무 많습니다. 잠시 후 다시 시도해 주세요."));
        } catch (RuntimeException error) {
            log.error("Failed to build route path", error);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to build route path"));
        }
    }

    private String requestIdentity(HttpServletRequest request) {
        String forwardedFor = request.getHeader("x-forwarded-for");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            forwardedFor = forwardedFor.split(",", 2)[0].trim();
        }
        return firstNonBlank(
            request.getHeader("cf-connecting-ip"),
            request.getHeader("x-real-ip"),
            forwardedFor,
            "unknown-client"
        );
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "unknown-client";
    }
}
