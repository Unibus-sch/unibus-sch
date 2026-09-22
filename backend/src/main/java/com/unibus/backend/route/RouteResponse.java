package com.unibus.backend.route;

import java.time.OffsetDateTime;
import java.util.List;

public record RouteResponse(
    String id,
    String name,
    String type,
    String description,
    String shuttleVariant,
    String color,
    String region,
    String schedule,
    String scheduleBasis,
    Integer intervalMinutes,
    Integer departureOffsetMinutes,
    Integer boardingWaitMinutes,
    String continuationRouteId,
    String duration,
    String fare,
    Boolean isActive,
    List<Stop> stops,
    List<ShapePoint> shapePoints,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
    public record Stop(
        String id,
        String name,
        Integer order,
        Double lat,
        Double lng,
        String arrivalTime
    ) {
    }

    public record ShapePoint(
        String id,
        String name,
        Integer afterStopOrder,
        Integer order,
        Double lat,
        Double lng
    ) {
    }
}
