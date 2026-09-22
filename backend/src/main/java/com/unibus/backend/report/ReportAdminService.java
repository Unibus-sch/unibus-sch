package com.unibus.backend.report;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.unibus.backend.common.api.ApiRequestException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
class ReportAdminService {

    private static final Set<String> STATUSES = Set.of("open", "in_progress", "resolved");
    private final ReportAdminRepository repository;
    private final ObjectMapper objectMapper;

    ReportAdminService(ReportAdminRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    List<ReportResponse> findAll(String status) {
        return repository.findAll(status != null && STATUSES.contains(status) ? status : null);
    }

    @Transactional
    ReportResponse update(String rawId, JsonNode body, String adminId) {
        Map<String, Object> updates = new LinkedHashMap<>();
        if (body != null && body.has("status")) {
            String status = body.get("status").isString() ? body.get("status").stringValue() : null;
            if (!STATUSES.contains(status)) throw badRequest("올바르지 않은 처리 상태입니다");
            updates.put("status", status);
            updates.put("resolved_at", "resolved".equals(status) ? OffsetDateTime.now() : null);
        }
        if (body != null && body.has("adminNote")) {
            String note = body.get("adminNote").asString("").trim();
            if (note.length() > 5_000) throw badRequest("관리자 메모가 너무 깁니다");
            updates.put("admin_note", note);
        }
        if (updates.isEmpty()) throw badRequest("변경할 내용이 없습니다");

        UUID id;
        try { id = UUID.fromString(rawId); }
        catch (IllegalArgumentException error) {
            throw new ApiRequestException(HttpStatus.INTERNAL_SERVER_ERROR, "문의 처리 상태 변경에 실패했습니다");
        }
        ReportResponse report = repository.update(id, updates).orElseThrow(() ->
            new ApiRequestException(HttpStatus.INTERNAL_SERVER_ERROR, "문의 처리 상태 변경에 실패했습니다")
        );
        try {
            repository.log(UUID.fromString(adminId), id, objectMapper.writeValueAsString(updates));
        } catch (JacksonException error) {
            throw new IllegalStateException(error);
        }
        return report;
    }

    private ApiRequestException badRequest(String message) {
        return new ApiRequestException(HttpStatus.BAD_REQUEST, message);
    }
}
