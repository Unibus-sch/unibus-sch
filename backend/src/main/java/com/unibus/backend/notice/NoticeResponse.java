package com.unibus.backend.notice;

import java.time.OffsetDateTime;
import java.util.List;

public record NoticeResponse(
    String id,
    String title,
    String content,
    String category,
    String priority,
    Boolean isPinned,
    Integer viewCount,
    String authorId,
    String authorName,
    List<String> imageUrls,
    String contentBelow,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
}
