package com.unibus.backend.user;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class UserAdminRepository {

    private static final String COLUMNS = "id, email, name, student_id, role, provider, created_at";
    private final JdbcTemplate jdbcTemplate;

    UserAdminRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    List<UserResponse> findAll() {
        return jdbcTemplate.query(
            "SELECT " + COLUMNS + " FROM users ORDER BY created_at DESC", (rs, row) -> map(rs)
        );
    }

    Optional<UserResponse> find(UUID id) {
        return jdbcTemplate.query(
            "SELECT " + COLUMNS + " FROM users WHERE id = ?", (rs, row) -> map(rs), id
        ).stream().findFirst();
    }

    Optional<UserResponse> updateName(UUID id, String name) {
        return jdbcTemplate.query("""
            UPDATE users SET name = ?, updated_at = NOW() WHERE id = ?
            RETURNING """ + COLUMNS, (rs, row) -> map(rs), name, id).stream().findFirst();
    }

    List<DrivenBus> findDrivenBuses(UUID driverId) {
        return jdbcTemplate.query("""
            SELECT id, is_running, current_driver_id FROM buses
            WHERE current_driver_id = ? OR assigned_driver_id = ?
            """, (rs, row) -> new DrivenBus(
                rs.getString("id"), rs.getBoolean("is_running"), rs.getString("current_driver_id")
            ), driverId, driverId);
    }

    void forceStop(String busId) {
        jdbcTemplate.queryForObject("SELECT admin_force_stop_bus(?)", Object.class, busId);
    }

    void clearAssignments(UUID driverId) {
        jdbcTemplate.update("""
            UPDATE buses SET current_driver_id = NULL, assigned_driver_id = NULL
            WHERE current_driver_id = ? OR assigned_driver_id = ?
            """, driverId, driverId);
    }

    void updateRole(UUID id, String role) {
        jdbcTemplate.update("UPDATE users SET role = ?, updated_at = NOW() WHERE id = ?", role, id);
    }

    void log(UUID adminId, String action, UUID targetId, String metadata) {
        jdbcTemplate.update("""
            INSERT INTO admin_action_logs (admin_id, action, target_type, target_id, metadata)
            VALUES (?, ?, 'user', ?, ?::jsonb)
            """, adminId, action, targetId.toString(), metadata);
    }

    private UserResponse map(ResultSet rs) throws SQLException {
        return new UserResponse(
            rs.getString("id"), rs.getString("email"), rs.getString("name"),
            rs.getString("student_id"), rs.getString("role"), rs.getString("provider"),
            rs.getObject("created_at", OffsetDateTime.class)
        );
    }

    record DrivenBus(String id, boolean running, String currentDriverId) {
    }
}
