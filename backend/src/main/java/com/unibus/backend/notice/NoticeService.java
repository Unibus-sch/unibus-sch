package com.unibus.backend.notice;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;

import com.unibus.backend.common.api.ApiRequestException;

@Service
class NoticeService {

    private static final int MAX_IMAGE_BYTES = 5 * 1024 * 1024;
    private static final int MAX_NOTICE_IMAGES = 10;
    private static final Set<String> VALID_CATEGORIES = Set.of("general", "route", "system", "lost");
    private static final Set<String> VALID_PRIORITIES = Set.of("low", "medium", "high", "urgent");
    private static final Map<String, String> IMAGE_EXTENSIONS = Map.of(
        "image/jpeg", "jpg", "image/png", "png", "image/webp", "webp", "image/gif", "gif"
    );

    private final NoticeRepository noticeRepository;
    private final NoticeStorageClient storageClient;

    NoticeService(NoticeRepository noticeRepository, NoticeStorageClient storageClient) {
        this.noticeRepository = noticeRepository;
        this.storageClient = storageClient;
    }

    @Transactional(readOnly = true)
    List<NoticeResponse> findAll() {
        return noticeRepository.findAll();
    }

    @Transactional
    Optional<NoticeResponse> findByIdAndIncrementViewCount(String rawId) {
        UUID id = parseUuid(rawId);
        if (id == null) {
            return Optional.empty();
        }

        Optional<NoticeResponse> notice = noticeRepository.findById(id);
        if (notice.isEmpty()) {
            return Optional.empty();
        }

        NoticeResponse current = notice.orElseThrow();
        int nextViewCount = Optional.ofNullable(current.viewCount()).orElse(0) + 1;
        noticeRepository.incrementViewCount(id, nextViewCount);
        return Optional.of(new NoticeResponse(
            current.id(), current.title(), current.content(), current.category(), current.priority(),
            current.isPinned(), nextViewCount, current.authorId(), current.authorName(),
            current.imageUrls(), current.contentBelow(), current.createdAt(), current.updatedAt()
        ));
    }

    @Transactional
    NoticeResponse create(JsonNode body, String adminId) {
        String title = text(body, "title").trim();
        String content = text(body, "content").trim();
        String contentBelow = text(body, "contentBelow").trim();
        String category = optionalText(body, "category", "general");
        String priority = optionalText(body, "priority", "medium");
        List<String> imageUrls = normalizeImageUrls(
            body == null ? null : body.get("imageUrls"), true,
            "공지 이미지는 전용 저장소의 이미지 10개까지만 사용할 수 있습니다"
        );
        boolean pinned = booleanValue(body, "isPinned", false);

        if (title.isEmpty() || content.isEmpty()) {
            throw badRequest("Missing required fields");
        }
        if (title.length() > 255 || content.length() > 20_000 || contentBelow.length() > 20_000) {
            throw badRequest("공지 내용이 허용 길이를 초과했습니다");
        }
        if (!VALID_CATEGORIES.contains(category) || !VALID_PRIORITIES.contains(priority)) {
            throw badRequest("공지 분류 또는 중요도가 올바르지 않습니다");
        }
        return noticeRepository.insert(
            UUID.fromString(adminId), title, content, category, priority, pinned, imageUrls, contentBelow
        );
    }

    @Transactional
    NoticeResponse update(String rawId, JsonNode body) {
        UUID id = parseUuid(rawId);
        if (id == null || noticeRepository.findById(id).isEmpty()) {
            throw new ApiRequestException(HttpStatus.NOT_FOUND, "Notice not found");
        }
        Map<String, Object> updates = new LinkedHashMap<>();
        if (has(body, "title")) {
            String value = string(body.get("title"));
            if (value == null || value.trim().isEmpty() || value.trim().length() > 255) {
                throw badRequest("Invalid notice title");
            }
            updates.put("title", value.trim());
        }
        if (has(body, "content")) {
            String value = string(body.get("content"));
            if (value == null || value.trim().isEmpty() || value.trim().length() > 20_000) {
                throw badRequest("Invalid notice content");
            }
            updates.put("content", value.trim());
        }
        if (has(body, "category")) {
            String value = string(body.get("category"));
            if (!VALID_CATEGORIES.contains(value)) throw badRequest("Invalid notice category");
            updates.put("category", value);
        }
        if (has(body, "priority")) {
            String value = string(body.get("priority"));
            if (!VALID_PRIORITIES.contains(value)) throw badRequest("Invalid notice priority");
            updates.put("priority", value);
        }
        if (has(body, "isPinned")) {
            if (!body.get("isPinned").isBoolean()) throw badRequest("Invalid pinned state");
            updates.put("is_pinned", body.get("isPinned").booleanValue());
        }
        if (has(body, "imageUrls")) {
            updates.put("image_urls", normalizeImageUrls(
                body.get("imageUrls"), false, "Invalid notice images"
            ).toArray(String[]::new));
        }
        if (has(body, "contentBelow")) {
            String value = string(body.get("contentBelow"));
            if (value == null || value.length() > 20_000) throw badRequest("Invalid notice content");
            updates.put("content_below", value.trim());
        }
        if (updates.isEmpty()) throw badRequest("No valid updates provided");
        return noticeRepository.update(id, updates).orElseThrow();
    }

    @Transactional
    void delete(String rawId) {
        UUID id = parseUuid(rawId);
        if (id == null || !noticeRepository.delete(id)) {
            throw new ApiRequestException(HttpStatus.NOT_FOUND, "Notice not found");
        }
    }

    String uploadImage(MultipartFile file) {
        if (file == null) throw badRequest("Image file is required");
        String contentType = file.getContentType();
        String extension = IMAGE_EXTENSIONS.get(contentType);
        if (extension == null) throw badRequest("JPEG, PNG, WebP, GIF 이미지만 업로드할 수 있습니다");
        if (file.getSize() <= 0 || file.getSize() > MAX_IMAGE_BYTES) {
            throw new ApiRequestException(HttpStatus.valueOf(413), "이미지는 5MB 이하여야 합니다");
        }
        try {
            byte[] bytes = file.getBytes();
            if (!hasValidSignature(contentType, bytes)) {
                throw badRequest("파일 형식과 실제 이미지 내용이 일치하지 않습니다");
            }
            return storageClient.upload(bytes, contentType, extension);
        } catch (java.io.IOException error) {
            throw new IllegalStateException("Failed to read image", error);
        }
    }

    private List<String> normalizeImageUrls(JsonNode node, boolean missingAsEmpty, String errorMessage) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return missingAsEmpty ? List.of() : invalidImages(errorMessage);
        }
        if (!node.isArray() || node.size() > MAX_NOTICE_IMAGES) return invalidImages(errorMessage);
        String origin = storageClient.storageOrigin().orElse("");
        List<String> urls = new java.util.ArrayList<>();
        for (JsonNode item : node) {
            String value = string(item);
            if (value == null || value.length() > 2_048) return invalidImages(errorMessage);
            try {
                URI uri = URI.create(value);
                String candidateOrigin = uri.getScheme() + "://" + uri.getHost()
                    + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
                if (!candidateOrigin.equals(origin)
                    || !uri.getPath().startsWith("/storage/v1/object/public/notice-images/")) {
                    return invalidImages(errorMessage);
                }
                urls.add(uri.toString());
            } catch (IllegalArgumentException error) {
                return invalidImages(errorMessage);
            }
        }
        return List.copyOf(urls);
    }

    private List<String> invalidImages(String message) {
        throw badRequest(message);
    }

    private boolean hasValidSignature(String type, byte[] bytes) {
        if ("image/jpeg".equals(type)) return bytes.length >= 3 && (bytes[0] & 255) == 0xff
            && (bytes[1] & 255) == 0xd8 && (bytes[2] & 255) == 0xff;
        if ("image/png".equals(type)) {
            int[] signature = {0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
            if (bytes.length < signature.length) return false;
            for (int index = 0; index < signature.length; index++) {
                if ((bytes[index] & 255) != signature[index]) return false;
            }
            return true;
        }
        if ("image/gif".equals(type)) {
            String header = new String(bytes, 0, Math.min(6, bytes.length), java.nio.charset.StandardCharsets.US_ASCII);
            return "GIF87a".equals(header) || "GIF89a".equals(header);
        }
        return "image/webp".equals(type) && bytes.length >= 12
            && "RIFF".equals(new String(bytes, 0, 4, java.nio.charset.StandardCharsets.US_ASCII))
            && "WEBP".equals(new String(bytes, 8, 4, java.nio.charset.StandardCharsets.US_ASCII));
    }

    private String text(JsonNode body, String field) {
        return body != null && body.has(field) && body.get(field).isString()
            ? body.get(field).stringValue() : "";
    }

    private String optionalText(JsonNode body, String field, String fallback) {
        String value = text(body, field);
        return value.isEmpty() ? fallback : value;
    }

    private boolean booleanValue(JsonNode body, String field, boolean fallback) {
        if (body == null || !body.has(field)) return fallback;
        if (!body.get(field).isBoolean()) throw badRequest("Invalid pinned state");
        return body.get(field).booleanValue();
    }

    private boolean has(JsonNode body, String field) {
        return body != null && body.has(field);
    }

    private String string(JsonNode node) {
        return node != null && node.isString() ? node.stringValue() : null;
    }

    private ApiRequestException badRequest(String message) {
        return new ApiRequestException(HttpStatus.BAD_REQUEST, message);
    }

    private UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
