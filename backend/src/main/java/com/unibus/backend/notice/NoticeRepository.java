package com.unibus.backend.notice;

import java.sql.Array;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class NoticeRepository {

    private static final String SELECT_NOTICE = """
        SELECT n.id, n.title, n.content, n.category, n.priority,
               n.is_pinned, n.view_count, n.image_urls, n.content_below,
               n.created_at, n.updated_at, u.id AS author_id, u.name AS author_name
        FROM notices n
        JOIN users u ON u.id = n.author_id
        """;

    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedJdbcTemplate;

    NoticeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.namedJdbcTemplate = new NamedParameterJdbcTemplate(jdbcTemplate);
    }

    List<NoticeResponse> findAll() {
        return jdbcTemplate.query(
            SELECT_NOTICE + " ORDER BY n.is_pinned DESC, n.created_at DESC",
            (resultSet, rowNumber) -> mapNotice(resultSet)
        );
    }

    Optional<NoticeResponse> findById(UUID id) {
        return jdbcTemplate.query(
            SELECT_NOTICE + " WHERE n.id = ?",
            (resultSet, rowNumber) -> mapNotice(resultSet),
            id
        ).stream().findFirst();
    }

    void incrementViewCount(UUID id, int nextViewCount) {
        jdbcTemplate.update("UPDATE notices SET view_count = ? WHERE id = ?", nextViewCount, id);
    }

    NoticeResponse insert(
        UUID authorId,
        String title,
        String content,
        String category,
        String priority,
        boolean pinned,
        List<String> imageUrls,
        String contentBelow
    ) {
        UUID id = jdbcTemplate.queryForObject("""
            INSERT INTO notices (
                title, content, category, priority, author_id, is_pinned, image_urls, content_below
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            RETURNING id
            """, UUID.class, title, content, category, priority, authorId, pinned,
            imageUrls.toArray(String[]::new), contentBelow);
        return findById(id).orElseThrow();
    }

    Optional<NoticeResponse> update(UUID id, Map<String, Object> updates) {
        if (updates.isEmpty()) {
            return findById(id);
        }
        String assignments = String.join(", ", updates.keySet().stream()
            .map(column -> column + " = :" + column)
            .toList());
        MapSqlParameterSource parameters = new MapSqlParameterSource(updates).addValue("id", id);
        namedJdbcTemplate.update(
            "UPDATE notices SET " + assignments + ", updated_at = NOW() WHERE id = :id",
            parameters
        );
        return findById(id);
    }

    boolean delete(UUID id) {
        return jdbcTemplate.update("DELETE FROM notices WHERE id = ?", id) > 0;
    }

    private NoticeResponse mapNotice(java.sql.ResultSet resultSet) throws SQLException {
        Integer viewCount = resultSet.getObject("view_count", Integer.class);
        return new NoticeResponse(
            resultSet.getString("id"),
            resultSet.getString("title"),
            resultSet.getString("content"),
            resultSet.getString("category"),
            resultSet.getString("priority"),
            resultSet.getObject("is_pinned", Boolean.class),
            viewCount,
            resultSet.getString("author_id"),
            resultSet.getString("author_name"),
            toStringList(resultSet.getArray("image_urls")),
            Optional.ofNullable(resultSet.getString("content_below")).orElse(""),
            resultSet.getObject("created_at", OffsetDateTime.class),
            resultSet.getObject("updated_at", OffsetDateTime.class)
        );
    }

    private List<String> toStringList(Array sqlArray) throws SQLException {
        if (sqlArray == null) {
            return List.of();
        }
        try {
            return Arrays.asList((String[]) sqlArray.getArray());
        } finally {
            sqlArray.free();
        }
    }
}
