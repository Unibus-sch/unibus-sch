package com.unibus.backend.driver;

import com.unibus.backend.auth.DriverRequest;
import com.unibus.backend.common.api.ApiRequestException;
import com.unibus.backend.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/driver")
public class DriverController {

    private static final Logger log = LoggerFactory.getLogger(DriverController.class);
    private final DriverService service;

    public DriverController(DriverService service) {
        this.service = service;
    }

    @GetMapping("/buses")
    ResponseEntity<?> buses(HttpServletRequest request) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                service.findBuses(DriverRequest.user(request).id())
            ));
        } catch (RuntimeException error) {
            log.error("Failed to fetch driver buses", error);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to fetch buses"));
        }
    }

    @PostMapping("/start")
    ResponseEntity<?> start(
        @RequestBody(required = false) JsonNode body,
        HttpServletRequest request
    ) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                service.start(body, DriverRequest.user(request).id())
            ));
        } catch (ApiRequestException error) {
            return ResponseEntity.status(error.status()).body(ApiResponse.error(error.getMessage()));
        } catch (RuntimeException error) {
            log.error("Failed to start driving", error);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to start driving"));
        }
    }

    @PutMapping("/progress")
    ResponseEntity<?> progress(
        @RequestBody(required = false) JsonNode body,
        HttpServletRequest request
    ) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                service.progress(body, DriverRequest.user(request).id())
            ));
        } catch (ApiRequestException error) {
            return ResponseEntity.status(error.status()).body(ApiResponse.error(error.getMessage()));
        } catch (RuntimeException error) {
            log.error("Failed to update trip progress", error);
            return ResponseEntity.internalServerError()
                .body(ApiResponse.error("Failed to update trip progress"));
        }
    }

    @PutMapping("/phase")
    ResponseEntity<?> phase(HttpServletRequest request) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                service.advancePhase(DriverRequest.user(request).id())
            ));
        } catch (ApiRequestException error) {
            return ResponseEntity.status(error.status()).body(ApiResponse.error(error.getMessage()));
        } catch (RuntimeException error) {
            log.error("Failed to update service phase", error);
            return ResponseEntity.internalServerError()
                .body(ApiResponse.error("Failed to update service phase"));
        }
    }

    @PostMapping("/location")
    ResponseEntity<?> location(
        @RequestBody(required = false) JsonNode body,
        HttpServletRequest request
    ) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                service.location(body, DriverRequest.user(request).id())
            ));
        } catch (ApiRequestException error) {
            return ResponseEntity.status(error.status()).body(ApiResponse.error(error.getMessage()));
        } catch (RuntimeException error) {
            log.error("Failed to update location", error);
            return ResponseEntity.internalServerError()
                .body(ApiResponse.error("Failed to update location"));
        }
    }

    @PostMapping("/stop")
    ResponseEntity<?> stop(HttpServletRequest request) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                service.stop(DriverRequest.user(request).id())
            ));
        } catch (ApiRequestException error) {
            return ResponseEntity.status(error.status()).body(ApiResponse.error(error.getMessage()));
        } catch (RuntimeException error) {
            log.error("Failed to stop driving", error);
            return ResponseEntity.internalServerError()
                .body(ApiResponse.error("Failed to stop driving"));
        }
    }

    @GetMapping("/status")
    ResponseEntity<?> status(HttpServletRequest request) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                service.status(DriverRequest.user(request).id())
            ));
        } catch (RuntimeException error) {
            log.error("Failed to restore driver status", error);
            return ResponseEntity.ok(ApiResponse.success(new DriverResponses.Status(null)));
        }
    }
}
