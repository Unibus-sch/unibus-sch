package com.unibus.backend.bus;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class BusAdminRepository {

    private static final String COLUMNS = """
        id, name, type, capacity, license_plate, status, is_running, current_driver_id,
        assigned_driver_id, current_route_id, created_at, updated_at
        """;
    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedJdbcTemplate;

    BusAdminRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.namedJdbcTemplate = new NamedParameterJdbcTemplate(jdbcTemplate);
    }

    RawBus insert(String id, String name, String type, int capacity, String plate, UUID routeId) {
        return jdbcTemplate.queryForObject("""
            INSERT INTO buses (id, name, type, capacity, license_plate, current_route_id, status)
            VALUES (?, ?, ?, ?, ?, ?, 'inactive')
            """ + " RETURNING " + COLUMNS,
            (rs, row) -> map(rs), id, name, type, capacity, plate, routeId
        );
    }

    Optional<RawBus> find(String id) {
        return jdbcTemplate.query(
            "SELECT " + COLUMNS + " FROM buses WHERE id = ?", (rs, row) -> map(rs), id
        ).stream().findFirst();
    }

    RawBus update(String id, Map<String, Object> updates) {
        String assignments = String.join(", ", updates.keySet().stream()
            .map(column -> column + " = :" + column).toList());
        namedJdbcTemplate.update(
            "UPDATE buses SET " + assignments + ", updated_at = NOW() WHERE id = :id",
            new MapSqlParameterSource(updates).addValue("id", id)
        );
        return find(id).orElseThrow();
    }

    boolean isDriver(UUID id) {
        Boolean result = jdbcTemplate.query(
            "SELECT role = 'driver' FROM users WHERE id = ?",
            rs -> rs.next() ? rs.getBoolean(1) : null, id
        );
        return Boolean.TRUE.equals(result);
    }

    void forceStop(String busId) {
        jdbcTemplate.queryForObject("SELECT admin_force_stop_bus(?)", Object.class, busId);
    }

    void log(UUID adminId, String action, String targetId, String metadataJson) {
        jdbcTemplate.update("""
            INSERT INTO admin_action_logs (admin_id, action, target_type, target_id, metadata)
            VALUES (?, ?, 'bus', ?, ?::jsonb)
            """, adminId, action, targetId, metadataJson);
    }

    boolean delete(String id) {
        return jdbcTemplate.update("DELETE FROM buses WHERE id = ?", id) > 0;
    }

    private RawBus map(ResultSet rs) throws SQLException {
        return new RawBus(
            rs.getString("id"), rs.getString("name"), rs.getString("type"),
            rs.getObject("capacity", Integer.class), rs.getString("license_plate"),
            rs.getString("status"), rs.getObject("is_running", Boolean.class),
            rs.getString("current_driver_id"), rs.getString("assigned_driver_id"),
            rs.getString("current_route_id"), rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class)
        );
    }

    record RawBus(
        String id, String name, String type, Integer capacity, String licensePlate, String status,
        Boolean running, String currentDriverId, String assignedDriverId, String currentRouteId,
        OffsetDateTime createdAt, OffsetDateTime updatedAt
    ) {
    }
}
