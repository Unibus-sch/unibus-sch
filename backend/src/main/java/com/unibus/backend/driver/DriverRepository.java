package com.unibus.backend.driver;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class DriverRepository {

    private static final String BUS_COLUMNS =
        "id, name, type, capacity, status, is_running, current_driver_id, "
            + "assigned_driver_id, current_route_id";
    private static final String TRIP_COLUMNS =
        "id, bus_id, route_id, origin_route_id, driver_id, status, current_stop_order, "
            + "service_phase, scheduled_event_at, planned_departure_at, one_loop_only, "
            + "started_at, ended_at, updated_at";
    private static final String ROUTE_COLUMNS =
        "id, name, type, shuttle_variant, color, description, region, schedule, "
            + "schedule_basis, interval_minutes, departure_offset_minutes, "
            + "boarding_wait_minutes, continuation_route_id, duration, fare";

    private final JdbcTemplate jdbcTemplate;

    DriverRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    void lockDriver(UUID driverId) {
        jdbcTemplate.queryForObject(
            "SELECT id FROM users WHERE id = ? FOR UPDATE", UUID.class, driverId
        );
    }

    Optional<BusRow> lockBus(String busId) {
        return jdbcTemplate.query(
            "SELECT " + BUS_COLUMNS + " FROM buses WHERE id = ? FOR UPDATE",
            (rs, row) -> mapBus(rs), busId
        ).stream().findFirst();
    }

    List<BusRow> findEligibleBuses(UUID driverId) {
        return jdbcTemplate.query("SELECT " + BUS_COLUMNS + """
             FROM buses
            WHERE status = 'active'
              AND (assigned_driver_id IS NULL OR assigned_driver_id = ?)
            ORDER BY id
            """, (rs, row) -> mapBus(rs), driverId);
    }

    Optional<BusRow> findRunningBus(UUID driverId, boolean lock) {
        String suffix = lock ? " FOR UPDATE" : "";
        return jdbcTemplate.query("SELECT " + BUS_COLUMNS + """
             FROM buses
            WHERE current_driver_id = ? AND is_running = TRUE
            ORDER BY id
            """ + suffix, (rs, row) -> mapBus(rs), driverId).stream().findFirst();
    }

    List<TripRow> findActiveTripsForDriver(UUID driverId, boolean lock) {
        String suffix = lock ? " FOR UPDATE" : "";
        return jdbcTemplate.query("SELECT " + TRIP_COLUMNS + """
             FROM bus_trips
            WHERE driver_id = ? AND status = 'active'
            ORDER BY started_at DESC
            """ + suffix, (rs, row) -> mapTrip(rs), driverId);
    }

    Optional<TripRow> findActiveTripForBus(String busId) {
        return jdbcTemplate.query("SELECT " + TRIP_COLUMNS + """
             FROM bus_trips
            WHERE bus_id = ? AND status = 'active'
            ORDER BY started_at DESC
            LIMIT 1
            """, (rs, row) -> mapTrip(rs), busId).stream().findFirst();
    }

    void restoreBusRoute(String busId, UUID routeId) {
        jdbcTemplate.update("UPDATE buses SET current_route_id = ? WHERE id = ?", routeId, busId);
    }

    void completeActiveTripsForDriver(UUID driverId) {
        jdbcTemplate.update("""
            UPDATE bus_trips
            SET status = 'completed', ended_at = NOW(), updated_at = NOW()
            WHERE driver_id = ? AND status = 'active'
            """, driverId);
    }

    void completeActiveTripsForBus(String busId) {
        jdbcTemplate.update("""
            UPDATE bus_trips
            SET status = 'completed', ended_at = NOW(), updated_at = NOW()
            WHERE bus_id = ? AND status = 'active'
            """, busId);
    }

    void clearRunningBusesForDriver(UUID driverId) {
        jdbcTemplate.update("""
            UPDATE buses
            SET is_running = FALSE, current_driver_id = NULL, updated_at = NOW()
            WHERE current_driver_id = ?
            """, driverId);
    }

    void startBus(String busId, UUID driverId, UUID routeId) {
        jdbcTemplate.update("""
            UPDATE buses
            SET is_running = TRUE, current_driver_id = ?, current_route_id = ?, updated_at = NOW()
            WHERE id = ?
            """, driverId, routeId, busId);
    }

    TripRow insertTrip(
        String busId,
        UUID routeId,
        UUID driverId,
        String phase,
        OffsetDateTime scheduledEventAt,
        OffsetDateTime plannedDepartureAt
    ) {
        return jdbcTemplate.queryForObject("""
            INSERT INTO bus_trips (
                bus_id, route_id, origin_route_id, driver_id, status, current_stop_order,
                service_phase, scheduled_event_at, planned_departure_at
            ) VALUES (?, ?, ?, ?, 'active', 0, ?, ?, ?)
            RETURNING """ + " " + TRIP_COLUMNS,
            (rs, row) -> mapTrip(rs), busId, routeId, routeId, driverId, phase,
            scheduledEventAt, plannedDepartureAt
        );
    }

    Optional<RouteRow> findRoute(UUID routeId) {
        if (routeId == null) return Optional.empty();
        return jdbcTemplate.query(
            "SELECT " + ROUTE_COLUMNS + " FROM routes WHERE id = ?",
            (rs, row) -> mapRoute(rs), routeId
        ).stream().findFirst();
    }

    Optional<RouteRow> findFallbackCampusLoop() {
        return jdbcTemplate.query("SELECT " + ROUTE_COLUMNS + """
             FROM routes
            WHERE shuttle_variant = 'campus_loop' AND is_active = TRUE
            ORDER BY created_at
            LIMIT 1
            """, (rs, row) -> mapRoute(rs)).stream().findFirst();
    }

    List<StopRow> findStops(UUID routeId) {
        if (routeId == null) return List.of();
        return jdbcTemplate.query("""
            SELECT id, stop_name, stop_order, latitude, longitude, arrival_time
            FROM route_stops
            WHERE route_id = ?
            ORDER BY stop_order
            """, (rs, row) -> new StopRow(
                rs.getString("id"), rs.getString("stop_name"),
                rs.getObject("stop_order", Integer.class), number(rs, "latitude"),
                number(rs, "longitude"), rs.getString("arrival_time")
            ), routeId);
    }

    TripRow updateTripProgress(UUID tripId, int stopOrder, String phase) {
        return jdbcTemplate.queryForObject("""
            UPDATE bus_trips
            SET current_stop_order = ?, service_phase = ?, updated_at = NOW()
            WHERE id = ? AND status = 'active'
            RETURNING """ + " " + TRIP_COLUMNS,
            (rs, row) -> mapTrip(rs), stopOrder, phase, tripId
        );
    }

    TripRow updateTripRoute(UUID tripId, UUID routeId, int stopOrder, String phase, boolean oneLoopOnly) {
        return jdbcTemplate.queryForObject("""
            UPDATE bus_trips
            SET route_id = ?, current_stop_order = ?, service_phase = ?, one_loop_only = ?,
                updated_at = NOW()
            WHERE id = ? AND status = 'active'
            RETURNING """ + " " + TRIP_COLUMNS,
            (rs, row) -> mapTrip(rs), routeId, stopOrder, phase, oneLoopOnly, tripId
        );
    }

    TripRow updateTripPhase(UUID tripId, String phase) {
        return jdbcTemplate.queryForObject("""
            UPDATE bus_trips SET service_phase = ?, updated_at = NOW()
            WHERE id = ? AND status = 'active'
            RETURNING """ + " " + TRIP_COLUMNS,
            (rs, row) -> mapTrip(rs), phase, tripId
        );
    }

    void updateBusRoute(String busId, UUID routeId) {
        jdbcTemplate.update(
            "UPDATE buses SET current_route_id = ?, updated_at = NOW() WHERE id = ?",
            routeId, busId
        );
    }

    void recordLocation(
        String busId,
        UUID tripId,
        double latitude,
        double longitude,
        double speed,
        int heading
    ) {
        jdbcTemplate.queryForObject(
            "SELECT record_bus_location(?, ?, ?::numeric, ?::numeric, ?::numeric, ?)", Boolean.class,
            busId, tripId, latitude, longitude, speed, heading
        );
    }

    void stopBus(String busId, UUID restoreRouteId) {
        jdbcTemplate.update("""
            UPDATE buses
            SET is_running = FALSE, current_driver_id = NULL,
                current_route_id = COALESCE(?, current_route_id), updated_at = NOW()
            WHERE id = ?
            """, restoreRouteId, busId);
    }

    private BusRow mapBus(ResultSet rs) throws SQLException {
        return new BusRow(
            rs.getString("id"), rs.getString("name"), rs.getString("type"),
            rs.getObject("capacity", Integer.class), rs.getString("status"),
            rs.getObject("is_running", Boolean.class), uuid(rs, "current_driver_id"),
            uuid(rs, "assigned_driver_id"), uuid(rs, "current_route_id")
        );
    }

    private TripRow mapTrip(ResultSet rs) throws SQLException {
        return new TripRow(
            uuid(rs, "id"), rs.getString("bus_id"), uuid(rs, "route_id"),
            uuid(rs, "origin_route_id"), uuid(rs, "driver_id"), rs.getString("status"),
            rs.getObject("current_stop_order", Integer.class), rs.getString("service_phase"),
            rs.getObject("scheduled_event_at", OffsetDateTime.class),
            rs.getObject("planned_departure_at", OffsetDateTime.class),
            rs.getObject("one_loop_only", Boolean.class),
            rs.getObject("started_at", OffsetDateTime.class),
            rs.getObject("ended_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class)
        );
    }

    private RouteRow mapRoute(ResultSet rs) throws SQLException {
        return new RouteRow(
            uuid(rs, "id"), rs.getString("name"), rs.getString("type"),
            rs.getString("shuttle_variant"), rs.getString("color"),
            rs.getString("description"), rs.getString("region"), rs.getString("schedule"),
            rs.getString("schedule_basis"), rs.getObject("interval_minutes", Integer.class),
            rs.getObject("departure_offset_minutes", Integer.class),
            rs.getObject("boarding_wait_minutes", Integer.class),
            uuid(rs, "continuation_route_id"), rs.getString("duration"), rs.getString("fare")
        );
    }

    private UUID uuid(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, UUID.class);
    }

    private Double number(ResultSet rs, String column) throws SQLException {
        Number value = (Number) rs.getObject(column);
        return value == null ? null : value.doubleValue();
    }

    record BusRow(
        String id, String name, String type, Integer capacity, String status, Boolean running,
        UUID currentDriverId, UUID assignedDriverId, UUID currentRouteId
    ) {
    }

    record TripRow(
        UUID id, String busId, UUID routeId, UUID originRouteId, UUID driverId, String status,
        Integer currentStopOrder, String servicePhase, OffsetDateTime scheduledEventAt,
        OffsetDateTime plannedDepartureAt, Boolean oneLoopOnly, OffsetDateTime startedAt,
        OffsetDateTime endedAt, OffsetDateTime updatedAt
    ) {
    }

    record RouteRow(
        UUID id, String name, String type, String shuttleVariant, String color, String description,
        String region, String schedule, String scheduleBasis, Integer intervalMinutes,
        Integer departureOffsetMinutes, Integer boardingWaitMinutes, UUID continuationRouteId,
        String duration, String fare
    ) {
    }

    record StopRow(
        String id, String name, Integer order, Double lat, Double lng, String arrivalTime
    ) {
    }
}
