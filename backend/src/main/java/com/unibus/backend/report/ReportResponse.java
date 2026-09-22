package com.unibus.backend.report;

import java.time.OffsetDateTime;

record ReportResponse(
    String id,
    String userId,
    String userName,
    String userEmail,
    String category,
    String title,
    String details,
    String status,
    String relatedBusId,
    String relatedRouteId,
    String adminNote,
    OffsetDateTime resolvedAt,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
}
