package com.unibus.backend.notification;

import com.unibus.backend.auth.AdminRequest;
import com.unibus.backend.common.api.ApiRequestException;
import com.unibus.backend.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/notifications")
public class NotificationAdminController {

    private static final Logger log = LoggerFactory.getLogger(NotificationAdminController.class);
    private final NotificationAdminService service;

    public NotificationAdminController(NotificationAdminService service) {
        this.service = service;
    }

    @PostMapping("/send")
    ResponseEntity<?> send(@RequestBody(required = false) JsonNode body, HttpServletRequest request) {
        try {
            return ResponseEntity.ok(ApiResponse.success(service.send(body, AdminRequest.user(request).id())));
        } catch (ApiRequestException error) {
            return ResponseEntity.status(error.status()).body(ApiResponse.error(error.getMessage()));
        } catch (RuntimeException error) {
            log.error("Failed to send notification", error);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to send notification"));
        }
    }

    @PostMapping("/send-existing")
    ResponseEntity<?> sendExisting(@RequestBody(required = false) JsonNode body, HttpServletRequest request) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                service.sendExisting(body, AdminRequest.user(request).id())
            ));
        } catch (ApiRequestException error) {
            return ResponseEntity.status(error.status()).body(ApiResponse.error(error.getMessage()));
        } catch (RuntimeException error) {
            log.error("Failed to send existing notice", error);
            return ResponseEntity.internalServerError().body(ApiResponse.error("푸시 발송에 실패했습니다"));
        }
    }

    @GetMapping("/history")
    ResponseEntity<?> history() {
        try {
            return ResponseEntity.ok(ApiResponse.success(service.history()));
        } catch (RuntimeException error) {
            log.error("Failed to fetch notification history", error);
            return ResponseEntity.internalServerError().body(ApiResponse.error("발송 이력을 불러오지 못했습니다"));
        }
    }
}
