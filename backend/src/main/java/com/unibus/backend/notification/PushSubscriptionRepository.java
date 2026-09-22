package com.unibus.backend.notification;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class PushSubscriptionRepository {

    private final JdbcTemplate jdbcTemplate;

    PushSubscriptionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    Optional<ExistingSubscription> findByEndpoint(String endpoint) {
        return jdbcTemplate.query("""
            SELECT id, user_id FROM push_subscriptions WHERE endpoint = ?
            """, (rs, row) -> new ExistingSubscription(
                rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class)
            ), endpoint).stream().findFirst();
    }

    UUID insert(UUID userId, String endpoint, String p256dh, String auth, String userAgent) {
        return jdbcTemplate.queryForObject("""
            INSERT INTO push_subscriptions (
                user_id, endpoint, p256dh, auth, user_agent, enabled, last_error
            ) VALUES (?, ?, ?, ?, ?, TRUE, NULL)
            RETURNING id
            """, UUID.class, userId, endpoint, p256dh, auth, userAgent);
    }

    UUID update(
        UUID id,
        UUID userId,
        String endpoint,
        String p256dh,
        String auth,
        String userAgent
    ) {
        return jdbcTemplate.queryForObject("""
            UPDATE push_subscriptions
            SET user_id = ?, endpoint = ?, p256dh = ?, auth = ?, user_agent = ?,
                enabled = TRUE, last_error = NULL, updated_at = NOW()
            WHERE id = ?
            RETURNING id
            """, UUID.class, userId, endpoint, p256dh, auth, userAgent, id);
    }

    void disable(UUID userId, String endpoint) {
        jdbcTemplate.update("""
            UPDATE push_subscriptions SET enabled = FALSE, updated_at = NOW()
            WHERE user_id = ? AND endpoint = ?
            """, userId, endpoint);
    }

    record ExistingSubscription(UUID id, UUID userId) {
    }
}
