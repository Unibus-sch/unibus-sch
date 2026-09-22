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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/reports")
public class ReportAdminController {

    private static final Logger log = LoggerFactory.getLogger(ReportAdminController.class);
    private final ReportAdminService service;

    public ReportAdminController(ReportAdminService service) {
        this.service = service;
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
