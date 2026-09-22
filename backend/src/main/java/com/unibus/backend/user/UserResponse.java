package com.unibus.backend.user;

import java.time.OffsetDateTime;

record UserResponse(
    String id,
    String email,
    String name,
    String studentId,
    String role,
    String provider,
    OffsetDateTime createdAt
) {
}
