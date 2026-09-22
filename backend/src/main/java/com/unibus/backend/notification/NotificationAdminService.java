package com.unibus.backend.notification;

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
class NotificationAdminService {

    private static final Set<String> TARGETS = Set.of("all", "campus", "commuter", "system");
    private final NotificationAdminRepository repository;
    private final WebPushSender pushSender;
    private final ObjectMapper objectMapper;
    private final PushEndpointPolicy endpointPolicy;

    NotificationAdminService(
        NotificationAdminRepository repository,
        WebPushSender pushSender,
        ObjectMapper objectMapper,
        PushEndpointPolicy endpointPolicy
    ) {
        this.repository = repository;
        this.pushSender = pushSender;
        this.objectMapper = objectMapper;
        this.endpointPolicy = endpointPolicy;
    }

    @Transactional
    Map<String, Object> send(JsonNode body, String adminId) {
        String title = text(body, "title").trim();
        String message = text(body, "message").trim();
        String target = text(body, "target");
        if (target.isEmpty()) target = "all";
        if (title.isEmpty() || message.isEmpty()) throw badRequest("Missing required fields");
        if (title.length() > 160 || message.length() > 5_000) {
            throw badRequest("Notification content is too long");
        }
        if (!TARGETS.contains(target)) throw badRequest("Invalid notification target");
        String category = Set.of("campus", "commuter").contains(target) ? "route"
            : "system".equals(target) ? "system" : "general";
        String priority = "system".equals(category) ? "high" : "medium";
        UUID adminUuid = UUID.fromString(adminId);
        NotificationAdminRepository.Notice notice = repository.insertNotice(
            adminUuid, title, message, category, priority
        );
        Map<String, Object> formatted = formatNotice(notice, repository.authorName(adminUuid));
        NotificationAdminRepository.PushResult push = push(target, notice);
        repository.record(notice.id(), target, push, adminUuid);
        return Map.of("notice", formatted, "push", push);
    }

    @Transactional
    NotificationAdminRepository.PushResult sendExisting(JsonNode body, String adminId) {
        String noticeId = text(body, "noticeId");
        String target = text(body, "target");
        if (target.isEmpty()) target = "all";
        if (noticeId.isEmpty() || !TARGETS.contains(target)) {
            throw badRequest("공지와 발송 대상을 확인해 주세요");
        }
        UUID id;
        try { id = UUID.fromString(noticeId); }
        catch (IllegalArgumentException error) {
            throw new ApiRequestException(HttpStatus.NOT_FOUND, "공지를 찾을 수 없습니다");
        }
        NotificationAdminRepository.Notice notice = repository.findNotice(id)
            .orElseThrow(() -> new ApiRequestException(HttpStatus.NOT_FOUND, "공지를 찾을 수 없습니다"));
        NotificationAdminRepository.PushResult push = push(target, notice);
        UUID adminUuid = UUID.fromString(adminId);
        repository.record(id, target, push, adminUuid);
        try {
            repository.logPush(adminUuid, id, objectMapper.writeValueAsString(Map.of(
                "target", target, "attempted", push.attempted(), "sent", push.sent(), "failed", push.failed()
            )));
        } catch (JacksonException error) {
            throw new IllegalStateException(error);
        }
        return push;
    }

    @Transactional(readOnly = true)
    List<NotificationAdminRepository.Delivery> history() {
        return repository.history();
    }

    private NotificationAdminRepository.PushResult push(
        String target,
        NotificationAdminRepository.Notice notice
    ) {
        if (!pushSender.isConfigured()) return new NotificationAdminRepository.PushResult(0, 0, 0);
        List<NotificationAdminRepository.Subscription> subscriptions = repository.subscriptions(target);
        int sent = 0;
        int failed = 0;
        String payload;
        try {
            payload = objectMapper.writeValueAsString(Map.of(
                "title", notice.title(), "body", notice.content(), "url", "/notice",
                "noticeId", notice.id().toString(), "createdAt", notice.createdAt()
            ));
        } catch (JacksonException error) {
            throw new IllegalStateException(error);
        }
        for (NotificationAdminRepository.Subscription subscription : subscriptions) {
            if (!endpointPolicy.allows(subscription.endpoint())) {
                failed++;
                repository.markSubscription(subscription.id(), false, "Blocked invalid push endpoint");
                continue;
            }
            try {
                int status = pushSender.send(subscription, payload);
                if (status >= 200 && status < 300) sent++;
                else {
                    failed++;
                    repository.markSubscription(subscription.id(), status != 404 && status != 410,
                        "Push service returned " + status);
                }
            } catch (Exception error) {
                failed++;
                repository.markSubscription(subscription.id(), true, "Push send failed");
            }
        }
        return new NotificationAdminRepository.PushResult(subscriptions.size(), sent, failed);
    }

    private Map<String, Object> formatNotice(NotificationAdminRepository.Notice notice, String authorName) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", notice.id().toString()); result.put("title", notice.title());
        result.put("content", notice.content()); result.put("category", notice.category());
        result.put("priority", notice.priority()); result.put("isPinned", notice.pinned());
        result.put("viewCount", notice.viewCount()); result.put("authorId", notice.authorId().toString());
        result.put("authorName", authorName == null ? "Admin" : authorName);
        result.put("imageUrls", notice.imageUrls()); result.put("contentBelow", notice.contentBelow());
        result.put("createdAt", notice.createdAt()); result.put("updatedAt", notice.updatedAt());
        return result;
    }

    private String text(JsonNode body, String field) {
        return body != null && body.has(field) && body.get(field).isString()
            ? body.get(field).stringValue() : "";
    }

    private ApiRequestException badRequest(String message) {
        return new ApiRequestException(HttpStatus.BAD_REQUEST, message);
    }
}
