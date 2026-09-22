package com.unibus.backend.auth;

import com.unibus.backend.common.api.ApiResponse;
import com.unibus.backend.common.web.RateLimitExceededException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);
    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/signup")
    ResponseEntity<?> signup(
        @RequestBody(required = false) JsonNode body,
        HttpServletRequest request
    ) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                authService.signup(body, requestIdentity(request))
            ));
        } catch (AuthRequestException error) {
            return ResponseEntity.status(error.status()).body(ApiResponse.error(error.getMessage()));
        } catch (RateLimitExceededException error) {
            return rateLimited(error);
        } catch (RuntimeException error) {
            log.error("Signup failed", error);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Signup failed"));
        }
    }

    @PostMapping("/login")
    ResponseEntity<?> login(
        @RequestBody(required = false) JsonNode body,
        HttpServletRequest request
    ) {
        try {
            return ResponseEntity.ok(authService.login(body, requestIdentity(request)));
        } catch (AuthRequestException error) {
            return ResponseEntity.status(error.status()).body(ApiResponse.error(error.getMessage()));
        } catch (RateLimitExceededException error) {
            return rateLimited(error);
        } catch (RuntimeException error) {
            log.error("Login failed", error);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Login failed"));
        }
    }

    @PostMapping("/kakao")
    ResponseEntity<?> kakao(
        @RequestBody(required = false) JsonNode body,
        HttpServletRequest request
    ) {
        try {
            return ResponseEntity.ok(authService.kakao(body, requestIdentity(request)));
        } catch (AuthRequestException error) {
            return ResponseEntity.status(error.status()).body(ApiResponse.error(error.getMessage()));
        } catch (RateLimitExceededException error) {
            return rateLimited(error);
        } catch (RuntimeException error) {
            log.error("Kakao login failed", error);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Kakao login failed"));
        }
    }

    @PostMapping("/logout")
    ResponseEntity<?> logout(
        @RequestHeader(name = "X-Auth-Token", required = false) String rawToken
    ) {
        try {
            authService.logout(rawToken);
            return ResponseEntity.ok(new AuthResponses.LogoutSuccess(true, "Logged out successfully"));
        } catch (RuntimeException error) {
            log.error("Logout failed", error);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Logout failed"));
        }
    }

    private ResponseEntity<ApiResponse<Void>> rateLimited(RateLimitExceededException error) {
        return ResponseEntity.status(429)
            .header("Retry-After", String.valueOf(error.retryAfterSeconds()))
            .body(ApiResponse.error("요청이 너무 많습니다. 잠시 후 다시 시도해 주세요."));
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
