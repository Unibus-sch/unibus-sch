package com.unibus.backend.notification;

import com.unibus.backend.auth.AuthenticatedRequest;
import com.unibus.backend.common.api.ApiRequestException;
import com.unibus.backend.common.api.ApiResponse;
import com.unibus.backend.common.web.RateLimitExceededException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/notifications")
public class PushSubscriptionController {

    private static final Logger log = LoggerFactory.getLogger(PushSubscriptionController.class);
    private final PushSubscriptionService service;

    public PushSubscriptionController(PushSubscriptionService service) {
        this.service = service;
    }

    @GetMapping("/vapid-public-key")
    ResponseEntity<?> publicKey() {
        return ResponseEntity.ok(ApiResponse.success(service.publicKey()));
    }

    @PostMapping("/subscribe")
    ResponseEntity<?> subscribe(
        @RequestBody(required = false) JsonNode body,
        @RequestHeader(name = "User-Agent", required = false) String userAgent,
        HttpServletRequest request
    ) {
        try {
            return ResponseEntity.ok(ApiResponse.success(service.subscribe(
                body, AuthenticatedRequest.user(request).id(), userAgent
            )));
        } catch (ApiRequestException error) {
            return ResponseEntity.status(error.status()).body(ApiResponse.error(error.getMessage()));
        } catch (RateLimitExceededException error) {
            return ResponseEntity.status(429)
                .header("Retry-After", String.valueOf(error.retryAfterSeconds()))
                .body(ApiResponse.error("요청이 너무 많습니다. 잠시 후 다시 시도해 주세요."));
        } catch (RuntimeException error) {
            log.error("Failed to save push subscription", error);
            return ResponseEntity.internalServerError()
                .body(ApiResponse.error("Failed to save push subscription"));
        }
    }

    @PostMapping("/unsubscribe")
    ResponseEntity<?> unsubscribe(
        @RequestBody(required = false) JsonNode body,
        HttpServletRequest request
    ) {
        try {
            service.unsubscribe(body, AuthenticatedRequest.user(request).id());
            return ResponseEntity.ok(java.util.Map.of("success", true));
        } catch (ApiRequestException error) {
            return ResponseEntity.status(error.status()).body(ApiResponse.error(error.getMessage()));
        } catch (RuntimeException error) {
            log.error("Failed to disable push subscription", error);
            return ResponseEntity.internalServerError()
                .body(ApiResponse.error("Failed to disable push subscription"));
        }
    }
}
