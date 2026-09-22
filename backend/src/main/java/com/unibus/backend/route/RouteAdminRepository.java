package com.unibus.backend.route;

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
class RouteAdminRepository {

    private static final String COLUMNS = """
        id, name, type, shuttle_variant, description, color, region, schedule,
        schedule_basis, interval_minutes, departure_offset_minutes, boarding_wait_minutes,
        continuation_route_id, duration, fare, is_active, created_at, updated_at
        """;
    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedJdbcTemplate;

    RouteAdminRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.namedJdbcTemplate = new NamedParameterJdbcTemplate(jdbcTemplate);
    }

    RawRoute insert(Map<String, Object> values) {
        MapSqlParameterSource parameters = new MapSqlParameterSource(values);
        return namedJdbcTemplate.query("""
            INSERT INTO routes (
                name, type, shuttle_variant, description, color, region, schedule,
                schedule_basis, interval_minutes, departure_offset_minutes,
                boarding_wait_minutes, continuation_route_id, duration, fare, is_active
            ) VALUES (
                :name, :type, :shuttle_variant, :description, :color, :region, :schedule,
                :schedule_basis, :interval_minutes, :departure_offset_minutes,
                :boarding_wait_minutes, CAST(:continuation_route_id AS uuid), :duration, :fare, :is_active
            )
            """ + " RETURNING " + COLUMNS, parameters, (rs, row) -> map(rs)).getFirst();
    }

    Optional<RawRoute> find(UUID id) {
        return jdbcTemplate.query(
            "SELECT " + COLUMNS + " FROM routes WHERE id = ?",
            (rs, row) -> map(rs), id
        ).stream().findFirst();
    }

    RawRoute update(UUID id, Map<String, Object> updates) {
        if (!updates.isEmpty()) {
            String assignments = String.join(", ", updates.keySet().stream()
                .map(column -> column.equals("continuation_route_id")
                    ? column + " = CAST(:" + column + " AS uuid)"
                    : column + " = :" + column)
                .toList());
            namedJdbcTemplate.update(
                "UPDATE routes SET " + assignments + ", updated_at = NOW() WHERE id = :id",
                new MapSqlParameterSource(updates).addValue("id", id)
            );
        }
        return find(id).orElseThrow();
    }

    void replaceDetails(
        UUID routeId,
        boolean replaceStops,
        String stopsJson,
        boolean replaceShapePoints,
        String shapePointsJson
    ) {
        jdbcTemplate.queryForObject(
            "SELECT replace_route_details(?, ?, ?::jsonb, ?, ?::jsonb)",
            Object.class,
            routeId, replaceStops, stopsJson, replaceShapePoints, shapePointsJson
        );
    }

    boolean delete(UUID id) {
        return jdbcTemplate.update("DELETE FROM routes WHERE id = ?", id) > 0;
    }

    private RawRoute map(ResultSet rs) throws SQLException {
        return new RawRoute(
            rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("type"),
            rs.getString("shuttle_variant"), rs.getString("description"), rs.getString("color"),
            rs.getString("region"), rs.getString("schedule"), rs.getString("schedule_basis"),
            rs.getObject("interval_minutes", Integer.class),
            rs.getObject("departure_offset_minutes", Integer.class),
            rs.getObject("boarding_wait_minutes", Integer.class),
            rs.getString("continuation_route_id"), rs.getString("duration"), rs.getString("fare"),
            rs.getObject("is_active", Boolean.class),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class)
        );
    }

    record RawRoute(
        UUID id, String name, String type, String shuttleVariant, String description, String color,
        String region, String schedule, String scheduleBasis, Integer intervalMinutes,
        Integer departureOffsetMinutes, Integer boardingWaitMinutes, String continuationRouteId,
        String duration, String fare, Boolean active, OffsetDateTime createdAt, OffsetDateTime updatedAt
    ) {
    }
}
