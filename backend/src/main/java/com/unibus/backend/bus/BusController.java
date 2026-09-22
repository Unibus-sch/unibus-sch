package com.unibus.backend.bus;

import com.unibus.backend.common.api.ApiResponse;
import com.unibus.backend.common.api.ApiRequestException;
import com.unibus.backend.auth.AdminRequest;
import com.unibus.backend.auth.SessionAuthenticator;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/buses")
public class BusController {

    private static final Logger log = LoggerFactory.getLogger(BusController.class);
    private final BusService busService;
    private final BusAdminService busAdminService;
    private final SessionAuthenticator sessionAuthenticator;

    public BusController(
        BusService busService,
        BusAdminService busAdminService,
        SessionAuthenticator sessionAuthenticator
    ) {
        this.busService = busService;
        this.busAdminService = busAdminService;
        this.sessionAuthenticator = sessionAuthenticator;
    }

    @GetMapping
    ResponseEntity<ApiResponse<?>> findAll(
        @RequestHeader(name = "X-Auth-Token", required = false) String token
    ) {
        try {
            return ResponseEntity.ok(ApiResponse.success(isAdmin(token)
                ? busService.findAllManaged() : busService.findAll()));
        } catch (RuntimeException error) {
            log.error("Failed to fetch buses", error);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to fetch buses"));
        }
    }

    @GetMapping("/{id}")
    ResponseEntity<ApiResponse<?>> findById(
        @PathVariable String id,
        @RequestHeader(name = "X-Auth-Token", required = false) String token
    ) {
        try {
            if (isAdmin(token)) {
                return busService.findManagedById(id)
                    .<ResponseEntity<ApiResponse<?>>>map(bus -> ResponseEntity.ok(ApiResponse.success(bus)))
                    .orElseGet(() -> ResponseEntity.status(404).body(ApiResponse.error("Bus not found")));
            }
            return busService.findById(id)
                .<ResponseEntity<ApiResponse<?>>>map(bus -> ResponseEntity.ok(ApiResponse.success(bus)))
                .orElseGet(() -> ResponseEntity.status(404).body(ApiResponse.error("Bus not found")));
        } catch (RuntimeException error) {
            log.error("Failed to fetch bus", error);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to fetch bus"));
        }
    }

    @GetMapping("/locations/latest")
    ResponseEntity<ApiResponse<?>> findLatestLocations() {
        try {
            return ResponseEntity.ok(ApiResponse.success(busService.findLatestLocations()));
        } catch (RuntimeException error) {
            log.error("Failed to fetch locations", error);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to fetch locations"));
        }
    }

    @PostMapping
    ResponseEntity<?> create(@RequestBody(required = false) JsonNode body) {
        try {
            return ResponseEntity.ok(ApiResponse.success(busAdminService.create(body)));
        } catch (ApiRequestException error) {
            return ResponseEntity.status(error.status()).body(ApiResponse.error(error.getMessage()));
        } catch (RuntimeException error) {
            log.error("Failed to create bus", error);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to create bus"));
        }
    }

    @PutMapping("/{id}")
    ResponseEntity<?> update(@PathVariable String id, @RequestBody(required = false) JsonNode body) {
        try {
            return ResponseEntity.ok(ApiResponse.success(busAdminService.update(id, body)));
        } catch (ApiRequestException error) {
            return ResponseEntity.status(error.status()).body(ApiResponse.error(error.getMessage()));
        } catch (RuntimeException error) {
            log.error("Failed to update bus", error);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to update bus"));
        }
    }

    @PostMapping("/{id}/force-stop")
    ResponseEntity<?> forceStop(
        @PathVariable String id,
        @RequestBody(required = false) JsonNode body,
        HttpServletRequest request
    ) {
        try {
            busAdminService.forceStop(id, AdminRequest.user(request).id(), body);
            return ResponseEntity.ok(new SuccessResponse(true));
        } catch (ApiRequestException error) {
            return ResponseEntity.status(error.status()).body(ApiResponse.error(error.getMessage()));
        } catch (RuntimeException error) {
            log.error("Failed to force-stop bus", error);
            return ResponseEntity.internalServerError().body(ApiResponse.error("강제 운행 종료에 실패했습니다"));
        }
    }

    @DeleteMapping("/{id}")
    ResponseEntity<?> delete(@PathVariable String id) {
        try {
            busAdminService.delete(id);
            return ResponseEntity.ok(new SuccessResponse(true));
        } catch (ApiRequestException error) {
            return ResponseEntity.status(error.status()).body(ApiResponse.error(error.getMessage()));
        } catch (RuntimeException error) {
            log.error("Failed to delete bus", error);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to delete bus"));
        }
    }

    private boolean isAdmin(String token) {
        if (token == null || token.isBlank()) return false;
        SessionAuthenticator.Validation validation = sessionAuthenticator.validate(token);
        return validation.status() == SessionAuthenticator.Validation.Status.VALID
            && "admin".equals(validation.user().role());
    }

    private record SuccessResponse(boolean success) {
    }
}
