package com.unibus.backend.route;

import com.unibus.backend.common.api.ApiResponse;
import com.unibus.backend.common.web.RateLimitExceededException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestController
class CampusController {
    private static final Logger log = LoggerFactory.getLogger(CampusController.class);
    private static final String CACHE_CONTROL = "public, max-age=300, stale-while-revalidate=3600";
    private final CampusPathService service;

    CampusController(CampusPathService service) { this.service = service; }

    @GetMapping("/campus/path")
    ResponseEntity<?> path(HttpServletRequest request) {
        try {
            String identity = request.getHeader("cf-connecting-ip");
            if (identity == null || identity.isBlank()) identity = request.getHeader("x-real-ip");
            if (identity == null || identity.isBlank()) identity = request.getHeader("x-forwarded-for");
            if (identity == null || identity.isBlank()) identity = "unknown-client";
            return ResponseEntity.ok().header("Cache-Control", CACHE_CONTROL)
                .body(ApiResponse.success(service.path(identity.split(",", 2)[0].trim())));
        } catch (RateLimitExceededException error) {
            return ResponseEntity.status(429).header("Retry-After", "600")
                .header("Cache-Control", CACHE_CONTROL)
                .body(ApiResponse.error("요청이 너무 많습니다. 잠시 후 다시 시도해 주세요."));
        } catch (RuntimeException error) {
            log.error("Campus route path generation failed");
            return ResponseEntity.internalServerError().body(ApiResponse.error("Internal server error"));
        }
    }
}
