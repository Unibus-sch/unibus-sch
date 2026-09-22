package com.unibus.backend.bus;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class BusRepository {

    private static final String SELECT_BUS = """
        SELECT b.id, b.name, b.type, b.capacity, b.status, b.is_running,
               b.license_plate, b.current_driver_id, current_driver.name AS current_driver_name,
               b.assigned_driver_id, assigned_driver.name AS assigned_driver_name,
               b.created_at, b.updated_at,
               r.id AS route_id, r.name AS route_name, r.color AS route_color
        FROM buses b
        LEFT JOIN routes r ON r.id = b.current_route_id
        LEFT JOIN users current_driver ON current_driver.id = b.current_driver_id
        LEFT JOIN users assigned_driver ON assigned_driver.id = b.assigned_driver_id
        """;

    private final JdbcTemplate jdbcTemplate;

    BusRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    List<BusResponses.ListItem> findAll() {
        List<BaseBus> buses = baseBuses();
        if (buses.isEmpty()) {
            return List.of();
        }

        Map<String, BusResponses.ActiveTrip> trips = jdbcTemplate.query("""
            SELECT id, bus_id, route_id, service_phase, planned_departure_at, current_stop_order
            FROM bus_trips
            WHERE status = 'active'
            """, (resultSet, rowNumber) -> Map.entry(
                resultSet.getString("bus_id"),
                new BusResponses.ActiveTrip(
                    resultSet.getString("id"),
                    resultSet.getString("route_id"),
                    resultSet.getString("service_phase"),
                    resultSet.getObject("planned_departure_at", OffsetDateTime.class),
                    resultSet.getObject("current_stop_order", Integer.class)
                )
            )).stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        Map<String, BusResponses.Location> locations = jdbcTemplate.query("""
            SELECT bus_id, latitude, longitude, speed, heading, timestamp
            FROM bus_latest_state
            """, (resultSet, rowNumber) -> Map.entry(
                resultSet.getString("bus_id"),
                mapLocation(resultSet)
            )).stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        return buses.stream().map(bus -> {
            BusResponses.Location location = locations.get(bus.id());
            return new BusResponses.ListItem(
                bus.id(), bus.name(), bus.type(), bus.capacity(), bus.status(), bus.isRunning(),
                bus.currentRoute(), trips.get(bus.id()), location,
                location == null ? null : location.timestamp()
            );
        }).toList();
    }

    List<BusResponses.ManagedListItem> findAllManaged() {
        List<BaseBus> buses = baseBuses();
        Map<String, BusResponses.ActiveTrip> trips = activeTrips();
        Map<String, BusResponses.Location> locations = latestLocationsByBus();
        return buses.stream().map(bus -> {
            BusResponses.Location location = locations.get(bus.id());
            return new BusResponses.ManagedListItem(
                bus.id(), bus.name(), bus.type(), bus.capacity(), bus.status(), bus.isRunning(),
                bus.currentRoute(), trips.get(bus.id()), location,
                location == null ? null : location.timestamp(), bus.licensePlate(),
                bus.currentDriverId(), bus.currentDriverName(), bus.assignedDriverId(),
                bus.assignedDriverName(), bus.createdAt(), bus.updatedAt()
            );
        }).toList();
    }

    Optional<BusResponses.Detail> findById(String id) {
        return jdbcTemplate.query(
            SELECT_BUS + " WHERE b.id = ?",
            (resultSet, rowNumber) -> mapBaseBus(resultSet),
            id
        ).stream().findFirst().map(bus -> new BusResponses.Detail(
            bus.id(), bus.name(), bus.type(), bus.capacity(), bus.status(), bus.isRunning(),
            bus.currentRoute(), findLatestHistoricalLocation(bus.id()).orElse(null)
        ));
    }

    Optional<BusResponses.ManagedDetail> findManagedById(String id) {
        return jdbcTemplate.query(
            SELECT_BUS + " WHERE b.id = ?",
            (resultSet, rowNumber) -> mapBaseBus(resultSet), id
        ).stream().findFirst().map(bus -> new BusResponses.ManagedDetail(
            bus.id(), bus.name(), bus.type(), bus.capacity(), bus.status(), bus.isRunning(),
            bus.currentRoute(), findLatestHistoricalLocation(bus.id()).orElse(null), bus.licensePlate(),
            bus.currentDriverId(), bus.currentDriverName(), bus.assignedDriverId(),
            bus.assignedDriverName(), bus.createdAt(), bus.updatedAt()
        ));
    }

    List<BusResponses.LatestLocation> findLatestLocations() {
        return jdbcTemplate.query("""
            SELECT bus_id, latitude, longitude, speed, heading, timestamp
            FROM bus_latest_state
            """, (resultSet, rowNumber) -> new BusResponses.LatestLocation(
                resultSet.getString("bus_id"),
                number(resultSet, "latitude"),
                number(resultSet, "longitude"),
                number(resultSet, "speed"),
                resultSet.getObject("heading", Integer.class),
                resultSet.getObject("timestamp", OffsetDateTime.class)
            ));
    }

    private Optional<BusResponses.Location> findLatestHistoricalLocation(String busId) {
        return jdbcTemplate.query("""
            SELECT latitude, longitude, speed, heading, timestamp
            FROM bus_locations
            WHERE bus_id = ?
            ORDER BY timestamp DESC
            LIMIT 1
            """, (resultSet, rowNumber) -> mapLocation(resultSet), busId).stream().findFirst();
    }

    private BaseBus mapBaseBus(ResultSet resultSet) throws SQLException {
        String routeId = resultSet.getString("route_id");
        return new BaseBus(
            resultSet.getString("id"),
            resultSet.getString("name"),
            toClientType(resultSet.getString("type")),
            resultSet.getObject("capacity", Integer.class),
            resultSet.getString("status"),
            resultSet.getObject("is_running", Boolean.class),
            routeId == null ? null : new BusResponses.CurrentRoute(
                routeId,
                resultSet.getString("route_name"),
                resultSet.getString("route_color")
            ),
            resultSet.getString("license_plate"), resultSet.getString("current_driver_id"),
            resultSet.getString("current_driver_name"), resultSet.getString("assigned_driver_id"),
            resultSet.getString("assigned_driver_name"),
            resultSet.getObject("created_at", OffsetDateTime.class),
            resultSet.getObject("updated_at", OffsetDateTime.class)
        );
    }

    private List<BaseBus> baseBuses() {
        return jdbcTemplate.query(SELECT_BUS + " ORDER BY b.id", (rs, row) -> mapBaseBus(rs));
    }

    private Map<String, BusResponses.ActiveTrip> activeTrips() {
        return jdbcTemplate.query("""
            SELECT id, bus_id, route_id, service_phase, planned_departure_at, current_stop_order
            FROM bus_trips WHERE status = 'active'
            """, (rs, row) -> Map.entry(rs.getString("bus_id"), new BusResponses.ActiveTrip(
                rs.getString("id"), rs.getString("route_id"), rs.getString("service_phase"),
                rs.getObject("planned_departure_at", OffsetDateTime.class),
                rs.getObject("current_stop_order", Integer.class)
            ))).stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private Map<String, BusResponses.Location> latestLocationsByBus() {
        return jdbcTemplate.query("""
            SELECT bus_id, latitude, longitude, speed, heading, timestamp FROM bus_latest_state
            """, (rs, row) -> Map.entry(rs.getString("bus_id"), mapLocation(rs)))
            .stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private BusResponses.Location mapLocation(ResultSet resultSet) throws SQLException {
        return new BusResponses.Location(
            number(resultSet, "latitude"),
            number(resultSet, "longitude"),
            number(resultSet, "speed"),
            resultSet.getObject("heading", Integer.class),
            resultSet.getObject("timestamp", OffsetDateTime.class)
        );
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

    private record BaseBus(
        String id,
        String name,
        String type,
        Integer capacity,
        String status,
        Boolean isRunning,
        BusResponses.CurrentRoute currentRoute,
        String licensePlate,
        String currentDriverId,
        String currentDriverName,
        String assignedDriverId,
        String assignedDriverName,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
    ) {
    }
}
