package com.unibus.backend.report;

import java.util.Set;
import java.util.UUID;
import com.unibus.backend.common.api.ApiRequestException;
import com.unibus.backend.common.api.EdgeJson;
import com.unibus.backend.common.web.RequestRateLimiter;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Service
class ReportCreateService {
    private static final Set<String> CATEGORIES = Set.of("location", "schedule", "notification", "login", "lost", "other");
    private final ReportAdminRepository repository;
    private final RequestRateLimiter limiter;

    ReportCreateService(ReportAdminRepository repository, RequestRateLimiter limiter) {
        this.repository = repository;
        this.limiter = limiter;
    }

    // Enforce before entering any write transaction, so rejected submissions also consume quota.
    void enforceLimit(String userId) { limiter.enforce("report-create", userId, 5, 600); }

    @Transactional
    ReportResponse create(JsonNode body, String userId) {
        String category = EdgeJson.truthy(field(body, "category")) ? text(body, "category") : "other";
        String title = text(body, "title").trim();
        String details = text(body, "details").trim();
        if (!CATEGORIES.contains(category) || title.isEmpty() || details.isEmpty()) {
            throw badRequest("문의 유형, 제목, 내용을 모두 입력해 주세요");
        }
        if (title.length() > 160 || details.length() > 5000) throw badRequest("문의 제목 또는 내용이 허용 길이를 초과했습니다");
        String bus = text(body, "relatedBusId");
        String route = text(body, "relatedRouteId");
        if (EdgeJson.truthy(field(body, "relatedBusId")) && !bus.matches("[A-Za-z0-9-]{1,20}")) throw badRequest("올바르지 않은 버스 식별자입니다");
        if (EdgeJson.truthy(field(body, "relatedRouteId")) && !route.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw badRequest("올바르지 않은 노선 식별자입니다");
        }
        return repository.create(UUID.fromString(userId), category, title, details,
            bus.isEmpty() ? null : bus, route.isEmpty() ? null : UUID.fromString(route));
    }

    private String text(JsonNode body, String field) {
        JsonNode value = field(body, field);
        return EdgeJson.truthy(value) ? EdgeJson.string(value) : "";
    }

    private JsonNode field(JsonNode body, String field) { return body == null ? null : body.get(field); }

    private ApiRequestException badRequest(String message) { return new ApiRequestException(HttpStatus.BAD_REQUEST, message); }
}
