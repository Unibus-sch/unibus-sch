package com.unibus.backend.notification;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class NotificationAdminRepository {

    private final JdbcTemplate jdbcTemplate;

    NotificationAdminRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    Notice insertNotice(UUID adminId, String title, String content, String category, String priority) {
        return jdbcTemplate.queryForObject("""
            INSERT INTO notices (
                title, content, category, priority, author_id, image_urls, content_below
            ) VALUES (?, ?, ?, ?, ?, ARRAY[]::text[], '')
            RETURNING id, title, content, category, priority, is_pinned, view_count,
                      author_id, image_urls, content_below, created_at, updated_at
            """, (rs, row) -> mapNotice(rs), title, content, category, priority, adminId);
    }

    Optional<Notice> findNotice(UUID id) {
        return jdbcTemplate.query("""
            SELECT id, title, content, category, priority, is_pinned, view_count,
                   author_id, image_urls, content_below, created_at, updated_at
            FROM notices WHERE id = ?
            """, (rs, row) -> mapNotice(rs), id).stream().findFirst();
    }

    String authorName(UUID id) {
        return jdbcTemplate.query(
            "SELECT name FROM users WHERE id = ?",
            rs -> rs.next() ? rs.getString(1) : "Admin", id
        );
    }

    List<Subscription> subscriptions(String target) {
        String roleCondition = switch (target) {
            case "campus" -> " AND u.role IN ('user', 'driver')";
            case "commuter" -> " AND u.role = 'user'";
            default -> "";
        };
        return jdbcTemplate.query("""
            SELECT p.id, p.endpoint, p.p256dh, p.auth
            FROM push_subscriptions p JOIN users u ON u.id = p.user_id
            WHERE p.enabled = TRUE
            """ + roleCondition, (rs, row) -> new Subscription(
                rs.getObject("id", UUID.class), rs.getString("endpoint"),
                rs.getString("p256dh"), rs.getString("auth")
            ));
    }

    void markSubscription(UUID id, boolean enabled, String lastError) {
        jdbcTemplate.update(
            "UPDATE push_subscriptions SET enabled = ?, last_error = ? WHERE id = ?",
            enabled, lastError, id
        );
    }

    void record(UUID noticeId, String target, PushResult result, UUID adminId) {
        jdbcTemplate.update("""
            INSERT INTO notification_deliveries (
                notice_id, target, attempted, sent, failed, created_by
            ) VALUES (?, ?, ?, ?, ?, ?)
            """, noticeId, target, result.attempted(), result.sent(), result.failed(), adminId);
    }

    void logPush(UUID adminId, UUID noticeId, String metadata) {
        jdbcTemplate.update("""
            INSERT INTO admin_action_logs (admin_id, action, target_type, target_id, metadata)
            VALUES (?, 'notice_push_sent', 'notice', ?, ?::jsonb)
            """, adminId, noticeId.toString(), metadata);
    }

    List<Delivery> history() {
        return jdbcTemplate.query("""
            SELECT d.id, d.notice_id, COALESCE(n.title, '삭제된 공지') AS notice_title,
                   d.target, d.attempted, d.sent, d.failed, d.created_at
            FROM notification_deliveries d LEFT JOIN notices n ON n.id = d.notice_id
            ORDER BY d.created_at DESC LIMIT 50
            """, (rs, row) -> new Delivery(
                rs.getString("id"), rs.getString("notice_id"), rs.getString("notice_title"),
                rs.getString("target"), rs.getInt("attempted"), rs.getInt("sent"),
                rs.getInt("failed"), rs.getObject("created_at", OffsetDateTime.class)
            ));
    }

    private Notice mapNotice(ResultSet rs) throws SQLException {
        java.sql.Array array = rs.getArray("image_urls");
        List<String> images = array == null ? List.of() : List.of((String[]) array.getArray());
        if (array != null) array.free();
        return new Notice(
            rs.getObject("id", UUID.class), rs.getString("title"), rs.getString("content"),
            rs.getString("category"), rs.getString("priority"), rs.getBoolean("is_pinned"),
            rs.getInt("view_count"), rs.getObject("author_id", UUID.class), images,
            Optional.ofNullable(rs.getString("content_below")).orElse(""),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class)
        );
    }

    record Subscription(UUID id, String endpoint, String p256dh, String auth) {
    }

    record PushResult(int attempted, int sent, int failed) {
    }

    record Notice(
        UUID id, String title, String content, String category, String priority, boolean pinned,
        int viewCount, UUID authorId, List<String> imageUrls, String contentBelow,
        OffsetDateTime createdAt, OffsetDateTime updatedAt
    ) {
    }

    record Delivery(
        String id, String noticeId, String noticeTitle, String target,
        int attempted, int sent, int failed, OffsetDateTime createdAt
    ) {
    }
}
