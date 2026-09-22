package com.unibus.backend.driver;

import java.time.OffsetDateTime;
import java.util.List;

final class DriverResponses {

    private DriverResponses() {
    }

    record Bus(
        String id,
        String name,
        String type,
        Integer capacity,
        String status,
        Boolean is_running,
        String current_driver_id,
        String assigned_driver_id,
        boolean is_assigned_to_me,
        boolean is_shared,
        Route currentRoute,
        ActiveTrip activeTrip
    ) {
    }

    record Route(
        String id,
        String name,
        String type,
        String shuttleVariant,
        String color,
        String description,
        String region,
        String schedule,
        String scheduleBasis,
        Integer intervalMinutes,
        Integer departureOffsetMinutes,
        Integer boardingWaitMinutes,
        String continuationRouteId,
        String duration,
        String fare,
        List<Stop> stops
    ) {
    }

    record Stop(
        String id,
        String name,
        Integer order,
        Double lat,
        Double lng,
        String arrivalTime
    ) {
    }

    record ActiveTrip(
        String id,
        String routeId,
        String status,
        Integer currentStopOrder,
        String originRouteId,
        String servicePhase,
        OffsetDateTime scheduledEventAt,
        OffsetDateTime plannedDepartureAt,
        Boolean oneLoopOnly,
        OffsetDateTime startedAt,
        OffsetDateTime updatedAt
    ) {
    }

    record Start(
        String busId,
        String driverId,
        String tripId,
        String servicePhase,
        OffsetDateTime scheduledEventAt,
        OffsetDateTime plannedDepartureAt,
        Boolean oneLoopOnly
    ) {
    }

    record Location(String busId, double lat, double lng) {
    }

    record StopResult(String busId) {
    }

    record Status(Bus activeBus) {
    }

    record Progress(
        String id,
        String routeId,
        Integer currentStopOrder,
        String servicePhase,
        Boolean oneLoopOnly,
        OffsetDateTime startedAt,
        OffsetDateTime updatedAt
    ) {
    }

    record Transition(
        String id,
        String routeId,
        Integer currentStopOrder,
        String servicePhase,
        Boolean oneLoopOnly,
        OffsetDateTime startedAt,
        OffsetDateTime updatedAt,
        Route currentRoute
    ) {
    }

    record Phase(
        String id,
        String routeId,
        Integer currentStopOrder,
        String servicePhase,
        Boolean oneLoopOnly,
        OffsetDateTime scheduledEventAt,
        OffsetDateTime plannedDepartureAt,
        OffsetDateTime startedAt,
        OffsetDateTime updatedAt
    ) {
    }
}
