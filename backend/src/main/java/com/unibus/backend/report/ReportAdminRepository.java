package com.unibus.backend.report;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class ReportAdminRepository {

    private static final String SELECT = """
        SELECT r.id, r.user_id, COALESCE(u.name, '알 수 없음') AS user_name,
               COALESCE(u.email, '') AS user_email, r.category, r.title, r.details,
               r.status, r.related_bus_id, r.related_route_id, r.admin_note,
               r.resolved_at, r.created_at, r.updated_at
        FROM user_reports r LEFT JOIN users u ON u.id = r.user_id
        """;
    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedJdbcTemplate;

    ReportAdminRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.namedJdbcTemplate = new NamedParameterJdbcTemplate(jdbcTemplate);
    }

    List<ReportResponse> findAll(String status) {
        if (status == null) {
            return jdbcTemplate.query(SELECT + " ORDER BY r.created_at DESC LIMIT 200", (rs, row) -> map(rs));
        }
        return jdbcTemplate.query(
            SELECT + " WHERE r.status = ? ORDER BY r.created_at DESC LIMIT 200",
            (rs, row) -> map(rs), status
        );
    }

    Optional<ReportResponse> update(UUID id, Map<String, Object> updates) {
        String assignments = String.join(", ", updates.keySet().stream()
            .map(column -> column + " = :" + column).toList());
        namedJdbcTemplate.update(
            "UPDATE user_reports SET " + assignments + ", updated_at = NOW() WHERE id = :id",
            new MapSqlParameterSource(updates).addValue("id", id)
        );
        return jdbcTemplate.query(SELECT + " WHERE r.id = ?", (rs, row) -> map(rs), id)
            .stream().findFirst();
    }

    void log(UUID adminId, UUID reportId, String metadata) {
        jdbcTemplate.update("""
            INSERT INTO admin_action_logs (admin_id, action, target_type, target_id, metadata)
            VALUES (?, 'report_updated', 'user_report', ?, ?::jsonb)
            """, adminId, reportId.toString(), metadata);
    }

    private ReportResponse map(ResultSet rs) throws SQLException {
        return new ReportResponse(
            rs.getString("id"), rs.getString("user_id"), rs.getString("user_name"),
            rs.getString("user_email"), rs.getString("category"), rs.getString("title"),
            rs.getString("details"), rs.getString("status"), rs.getString("related_bus_id"),
            rs.getString("related_route_id"), Optional.ofNullable(rs.getString("admin_note")).orElse(""),
            rs.getObject("resolved_at", OffsetDateTime.class),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class)
        );
    }
}
