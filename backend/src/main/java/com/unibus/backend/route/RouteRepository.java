package com.unibus.backend.route;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Repository
class RouteRepository {

    private static final String SELECT_ROUTE = """
        SELECT id, name, type, description, shuttle_variant, color, region, schedule,
               schedule_basis, interval_minutes, departure_offset_minutes,
               boarding_wait_minutes, continuation_route_id, duration, fare,
               is_active, created_at, updated_at
        FROM routes
        """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    RouteRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    List<RouteResponse> findAll() {
        return jdbcTemplate.query(
            SELECT_ROUTE + " ORDER BY type, name",
            (resultSet, rowNumber) -> mapRoute(resultSet)
        ).stream().map(this::withDetails).toList();
    }

    Optional<RouteResponse> findById(UUID id) {
        return jdbcTemplate.query(
            SELECT_ROUTE + " WHERE id = ?",
            (resultSet, rowNumber) -> mapRoute(resultSet),
            id
        ).stream().findFirst().map(this::withDetails);
    }

    List<RouteResponse.Stop> findStops(UUID routeId) {
        return jdbcTemplate.query("""
            SELECT id, stop_name, stop_order, latitude, longitude, arrival_time
            FROM route_stops
            WHERE route_id = ?
            ORDER BY stop_order
            """, (resultSet, rowNumber) -> new RouteResponse.Stop(
                resultSet.getString("id"),
                resultSet.getString("stop_name"),
                resultSet.getObject("stop_order", Integer.class),
                number(resultSet, "latitude"),
                number(resultSet, "longitude"),
                resultSet.getString("arrival_time")
            ), routeId);
    }

    List<RouteResponse.ShapePoint> findShapePoints(UUID routeId) {
        return jdbcTemplate.query("""
            SELECT id, name, after_stop_order, point_order, latitude, longitude
            FROM route_shape_points
            WHERE route_id = ?
            ORDER BY after_stop_order, point_order
            """, (resultSet, rowNumber) -> new RouteResponse.ShapePoint(
                resultSet.getString("id"),
                resultSet.getString("name"),
                resultSet.getObject("after_stop_order", Integer.class),
                resultSet.getObject("point_order", Integer.class),
                number(resultSet, "latitude"),
                number(resultSet, "longitude")
            ), routeId);
    }

    Optional<CachedPath> findCachedPath(UUID routeId) {
        return jdbcTemplate.query(
            "SELECT input_hash, path::text AS path FROM route_path_cache WHERE route_id = ?",
            (resultSet, rowNumber) -> new CachedPath(
                resultSet.getString("input_hash"),
                parsePath(resultSet.getString("path"))
            ),
            routeId
        ).stream().findFirst();
    }

    void saveCachedPath(UUID routeId, String inputHash, List<List<Double>> path) {
        if (path.size() <= 1) {
            return;
        }
        try {
            jdbcTemplate.update("""
                INSERT INTO route_path_cache (route_id, input_hash, path, generated_at)
                VALUES (?, ?, ?::jsonb, NOW())
                ON CONFLICT (route_id) DO UPDATE SET
                    input_hash = EXCLUDED.input_hash,
                    path = EXCLUDED.path,
                    generated_at = EXCLUDED.generated_at
                """, routeId, inputHash, objectMapper.writeValueAsString(path));
        } catch (JacksonException error) {
            throw new IllegalStateException("Failed to encode route path", error);
        }
    }

    void updateStopCoordinates(UUID stopId, double lat, double lng) {
        jdbcTemplate.update(
            "UPDATE route_stops SET latitude = ?, longitude = ? WHERE id = ?",
            lat, lng, stopId
        );
    }

    private RouteResponse withDetails(RouteResponse route) {
        UUID id = UUID.fromString(route.id());
        return new RouteResponse(
            route.id(), route.name(), route.type(), route.description(), route.shuttleVariant(),
            route.color(), route.region(), route.schedule(), route.scheduleBasis(),
            route.intervalMinutes(), route.departureOffsetMinutes(), route.boardingWaitMinutes(),
            route.continuationRouteId(), route.duration(), route.fare(), route.isActive(),
            findStops(id), findShapePoints(id), route.createdAt(), route.updatedAt()
        );
    }

    private RouteResponse mapRoute(ResultSet resultSet) throws SQLException {
        return new RouteResponse(
            resultSet.getString("id"),
            resultSet.getString("name"),
            toClientType(resultSet.getString("type")),
            resultSet.getString("description"),
            resultSet.getString("shuttle_variant"),
            resultSet.getString("color"),
            resultSet.getString("region"),
            resultSet.getString("schedule"),
            resultSet.getString("schedule_basis"),
            resultSet.getObject("interval_minutes", Integer.class),
            resultSet.getObject("departure_offset_minutes", Integer.class),
            resultSet.getObject("boarding_wait_minutes", Integer.class),
            resultSet.getString("continuation_route_id"),
            resultSet.getString("duration"),
            resultSet.getString("fare"),
            resultSet.getObject("is_active", Boolean.class),
            List.of(),
            List.of(),
            resultSet.getObject("created_at", OffsetDateTime.class),
            resultSet.getObject("updated_at", OffsetDateTime.class)
        );
    }

    private List<List<Double>> parsePath(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() { });
        } catch (JacksonException error) {
            throw new IllegalStateException("Failed to decode cached route path", error);
        }
    }

    private Double number(ResultSet resultSet, String column) throws SQLException {
        Number value = (Number) resultSet.getObject(column);
        return value == null ? null : value.doubleValue();
    }

    private String toClientType(String type) {
        return switch (type) {
            case "shuttle" -> "campus";
            case "commute" -> "commuter";
            default -> type;
        };
    }

    record CachedPath(String inputHash, List<List<Double>> path) {
    }
}
