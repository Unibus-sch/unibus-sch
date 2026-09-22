package com.unibus.backend.user;

import com.unibus.backend.auth.AdminRequest;
import com.unibus.backend.common.api.ApiRequestException;
import com.unibus.backend.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/users")
public class UserAdminController {

    private static final Logger log = LoggerFactory.getLogger(UserAdminController.class);
    private final UserAdminService service;

    public UserAdminController(UserAdminService service) {
        this.service = service;
    }

    @GetMapping
    ResponseEntity<?> findAll() {
        try {
            return ResponseEntity.ok(ApiResponse.success(service.findAll()));
        } catch (RuntimeException error) {
            log.error("Failed to fetch users", error);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to fetch users"));
        }
    }

    @PutMapping("/{id}")
    ResponseEntity<?> updateName(
        @PathVariable String id,
        @RequestBody(required = false) JsonNode body,
        HttpServletRequest request
    ) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                service.updateName(id, body, AdminRequest.user(request).id())
            ));
        } catch (ApiRequestException error) {
            return ResponseEntity.status(error.status()).body(ApiResponse.error(error.getMessage()));
        } catch (RuntimeException error) {
            log.error("Failed to update user", error);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to update user"));
        }
    }

    @PutMapping("/{id}/role")
    ResponseEntity<?> updateRole(
        @PathVariable String id,
        @RequestBody(required = false) JsonNode body,
        HttpServletRequest request
    ) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                service.updateRole(id, body, AdminRequest.user(request).id())
            ));
        } catch (ApiRequestException error) {
            return ResponseEntity.status(error.status()).body(ApiResponse.error(error.getMessage()));
        } catch (RuntimeException error) {
            log.error("Failed to update role", error);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to update role"));
        }
    }
}
