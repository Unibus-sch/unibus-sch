package com.unibus.backend.report;

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
import org.springframework.web.bind.annotation.PostMapping;
import com.unibus.backend.auth.AuthenticatedRequest;
import com.unibus.backend.common.web.RateLimitExceededException;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/reports")
public class ReportAdminController {

    private static final Logger log = LoggerFactory.getLogger(ReportAdminController.class);
    private final ReportAdminService service;
    private final ReportCreateService createService;
    private final ObjectMapper mapper;

    public ReportAdminController(ReportAdminService service, ReportCreateService createService, ObjectMapper mapper) {
        this.service = service;
        this.createService = createService;
        this.mapper = mapper;
    }

    @PostMapping
    ResponseEntity<?> create(@RequestBody(required = false) String rawBody, HttpServletRequest request) {
        try {
            String userId = AuthenticatedRequest.user(request).id();
            createService.enforceLimit(userId);
            JsonNode body = mapper.readTree(rawBody);
            if (body == null || body.isNull()) throw new IllegalArgumentException("Missing report JSON object");
            return ResponseEntity.ok(ApiResponse.success(createService.create(body, userId)));
        } catch (ApiRequestException error) {
            return ResponseEntity.status(error.status()).body(ApiResponse.error(error.getMessage()));
        } catch (RateLimitExceededException error) {
            return ResponseEntity.status(429).header("Retry-After", "600")
                .body(ApiResponse.error("요청이 너무 많습니다. 잠시 후 다시 시도해 주세요."));
        } catch (RuntimeException error) {
            log.error("Report creation failed");
            return ResponseEntity.internalServerError().body(ApiResponse.error("문의 접수에 실패했습니다"));
        }
    }

    @GetMapping
    ResponseEntity<?> findAll(@RequestParam(required = false) String status) {
        try {
            return ResponseEntity.ok(ApiResponse.success(service.findAll(status)));
        } catch (RuntimeException error) {
            log.error("Failed to fetch reports", error);
            return ResponseEntity.internalServerError().body(ApiResponse.error("문의 목록을 불러오지 못했습니다"));
        }
    }

    @PutMapping("/{id}")
    ResponseEntity<?> update(
        @PathVariable String id,
        @RequestBody(required = false) JsonNode body,
        HttpServletRequest request
    ) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                service.update(id, body, AdminRequest.user(request).id())
            ));
        } catch (ApiRequestException error) {
            return ResponseEntity.status(error.status()).body(ApiResponse.error(error.getMessage()));
        } catch (RuntimeException error) {
            log.error("Failed to update report", error);
            return ResponseEntity.internalServerError().body(ApiResponse.error("문의 처리 상태 변경에 실패했습니다"));
        }
    }
}
