package com.unibus.backend.bus;

import java.time.OffsetDateTime;

final class BusResponses {

    private BusResponses() {
    }

    record ListItem(
        String id,
        String name,
        String type,
        Integer capacity,
        String status,
        Boolean isRunning,
        CurrentRoute currentRoute,
        ActiveTrip activeTrip,
        Location location,
        OffsetDateTime lastLocationAt
    ) {
    }

    record ManagedListItem(
        String id, String name, String type, Integer capacity, String status, Boolean isRunning,
        CurrentRoute currentRoute, ActiveTrip activeTrip, Location location, OffsetDateTime lastLocationAt,
        String licensePlate, String currentDriverId, String currentDriverName,
        String assignedDriverId, String assignedDriverName, OffsetDateTime createdAt, OffsetDateTime updatedAt
    ) {
    }

    record Detail(
        String id,
        String name,
        String type,
        Integer capacity,
        String status,
        Boolean isRunning,
        CurrentRoute currentRoute,
        Location location
    ) {
    }

    record ManagedDetail(
        String id, String name, String type, Integer capacity, String status, Boolean isRunning,
        CurrentRoute currentRoute, Location location, String licensePlate, String currentDriverId,
        String currentDriverName, String assignedDriverId, String assignedDriverName,
        OffsetDateTime createdAt, OffsetDateTime updatedAt
    ) {
    }

    record CurrentRoute(String id, String name, String color) {
    }

    record ActiveTrip(
        String id,
        String routeId,
        String servicePhase,
        OffsetDateTime plannedDepartureAt,
        Integer currentStopOrder
    ) {
    }

    record Location(
        Double lat,
        Double lng,
        Double speed,
        Integer heading,
        OffsetDateTime timestamp
    ) {
    }

    record LatestLocation(
        String busId,
        Double lat,
        Double lng,
        Double speed,
        Integer heading,
        OffsetDateTime timestamp
    ) {
    }
}
