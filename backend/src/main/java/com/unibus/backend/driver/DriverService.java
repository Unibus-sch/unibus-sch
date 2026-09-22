package com.unibus.backend.driver;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.unibus.backend.common.api.ApiRequestException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Service
class DriverService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("H:mm");

    private final DriverRepository repository;
    private final Clock clock;

    DriverService(DriverRepository repository) {
        this.repository = repository;
        this.clock = Clock.systemUTC();
    }

    @Transactional(readOnly = true)
    List<DriverResponses.Bus> findBuses(String rawDriverId) {
        UUID driverId = UUID.fromString(rawDriverId);
        return repository.findEligibleBuses(driverId).stream()
            .map(bus -> formatBus(bus, driverId)).toList();
    }

    @Transactional
    DriverResponses.Start start(JsonNode body, String rawDriverId) {
        String busId = text(body, "busId").trim();
        if (busId.isEmpty()) throw badRequest("busId is required");

        UUID driverId = UUID.fromString(rawDriverId);
        repository.lockDriver(driverId);
        DriverRepository.BusRow bus = repository.lockBus(busId)
            .orElseThrow(() -> new ApiRequestException(HttpStatus.NOT_FOUND, "Bus not found"));
        if (!"active".equals(bus.status())) {
            throw new ApiRequestException(HttpStatus.CONFLICT, "운행 가능한 상태의 버스가 아닙니다");
        }
        if (bus.assignedDriverId() != null && !bus.assignedDriverId().equals(driverId)) {
            throw new ApiRequestException(HttpStatus.FORBIDDEN, "본인에게 배정된 버스만 운행할 수 있습니다");
        }
        if (Boolean.TRUE.equals(bus.running()) && bus.currentDriverId() != null
            && !bus.currentDriverId().equals(driverId)) {
            throw new ApiRequestException(HttpStatus.CONFLICT, "이미 다른 기사가 운행 중인 버스입니다");
        }

        List<DriverRepository.TripRow> previousTrips = repository.findActiveTripsForDriver(driverId, true);
        UUID assignedRouteId = previousTrips.stream()
            .filter(trip -> busId.equals(trip.busId()))
            .map(DriverRepository.TripRow::originRouteId)
            .filter(java.util.Objects::nonNull)
            .findFirst()
            .orElse(bus.currentRouteId());
        previousTrips.stream()
            .filter(trip -> trip.originRouteId() != null)
            .forEach(trip -> repository.restoreBusRoute(trip.busId(), trip.originRouteId()));

        DriverRepository.RouteRow route = repository.findRoute(assignedRouteId).orElse(null);
        ServiceTimes times = nextServiceTimes(route);
        String phase = initialServicePhase(route == null ? null : route.shuttleVariant());

        repository.completeActiveTripsForDriver(driverId);
        repository.completeActiveTripsForBus(busId);
        repository.clearRunningBusesForDriver(driverId);
        repository.startBus(busId, driverId, assignedRouteId);

        DriverRepository.TripRow trip;
        try {
            trip = repository.insertTrip(
                busId, assignedRouteId, driverId, phase,
                times.scheduledEventAt(), times.plannedDepartureAt()
            );
        } catch (DataIntegrityViolationException error) {
            throw new ApiRequestException(HttpStatus.CONFLICT, "이미 다른 기사가 운행 중인 버스입니다");
        }
        return new DriverResponses.Start(
            busId, rawDriverId, trip.id().toString(), trip.servicePhase(),
            trip.scheduledEventAt(), trip.plannedDepartureAt(), trip.oneLoopOnly()
        );
    }

    @Transactional
    Object progress(JsonNode body, String rawDriverId) {
        double rawOrder = jsNumber(body == null ? null : body.get("stopOrder"), Double.NaN);
        if (!Double.isFinite(rawOrder) || rawOrder != Math.rint(rawOrder) || rawOrder < 0) {
            throw badRequest("올바른 정류장 순서가 필요합니다");
        }
        int stopOrder = (int) rawOrder;
        UUID driverId = UUID.fromString(rawDriverId);
        repository.lockDriver(driverId);
        DriverRepository.TripRow trip = activeTrip(driverId);
        if ("waiting_station".equals(trip.servicePhase())) {
            throw new ApiRequestException(
                HttpStatus.CONFLICT, "학생 탑승 완료 후 신창역 출발을 먼저 처리해 주세요"
            );
        }

        DriverRepository.RouteRow route = repository.findRoute(trip.routeId()).orElse(null);
        List<DriverRepository.StopRow> stops = repository.findStops(trip.routeId());
        int lastStopOrder = stops.isEmpty() || stops.get(stops.size() - 1).order() == null
            ? 0 : stops.get(stops.size() - 1).order();
        if (lastStopOrder > 0 && stopOrder > lastStopOrder) {
            throw badRequest("노선 범위를 벗어난 정류장입니다");
        }

        if (stopOrder == 0 && Boolean.TRUE.equals(trip.oneLoopOnly()) && route != null
            && "campus_loop".equals(route.shuttleVariant())
            && trip.currentStopOrder() != null && trip.currentStopOrder() >= lastStopOrder) {
            return progressResponse(repository.updateTripPhase(trip.id(), "return_to_parking"));
        }

        boolean reachedRearGate = route != null
            && "station_to_campus_loop".equals(route.shuttleVariant())
            && stops.stream().anyMatch(stop -> stopOrder == valueOrZero(stop.order())
                && stop.name() != null && stop.name().contains("후문"));
        if (reachedRearGate) {
            UUID continuationId = route.continuationRouteId();
            DriverRepository.RouteRow continuation = continuationId == null
                ? repository.findFallbackCampusLoop().orElse(null)
                : repository.findRoute(continuationId).orElse(null);
            if (continuation == null) {
                throw new ApiRequestException(
                    HttpStatus.CONFLICT,
                    "연결할 학내순환 노선이 없습니다. 관리자 노선 설정을 확인해 주세요"
                );
            }
            List<DriverRepository.StopRow> continuationStops = repository.findStops(continuation.id());
            int rearGateOrder = continuationStops.stream()
                .filter(stop -> stop.name() != null && stop.name().contains("후문"))
                .map(DriverRepository.StopRow::order)
                .filter(java.util.Objects::nonNull)
                .findFirst().orElse(0);
            DriverRepository.TripRow transitioned = repository.updateTripRoute(
                trip.id(), continuation.id(), rearGateOrder, "campus_loop", true
            );
            repository.updateBusRoute(trip.busId(), continuation.id());
            return new DriverResponses.Transition(
                transitioned.id().toString(), id(transitioned.routeId()),
                transitioned.currentStopOrder(), transitioned.servicePhase(),
                transitioned.oneLoopOnly(), transitioned.startedAt(), transitioned.updatedAt(),
                formatRoute(continuation, continuationStops)
            );
        }

        String phase = stopOrder == lastStopOrder && route != null
            && ("campus_to_station".equals(route.shuttleVariant())
                || "station_to_campus".equals(route.shuttleVariant()))
            ? "return_to_parking" : trip.servicePhase();
        return progressResponse(repository.updateTripProgress(trip.id(), stopOrder, phase));
    }

    @Transactional
    DriverResponses.Phase advancePhase(String rawDriverId) {
        UUID driverId = UUID.fromString(rawDriverId);
        repository.lockDriver(driverId);
        DriverRepository.TripRow active = activeTrip(driverId);
        if (!"waiting_station".equals(active.servicePhase())) {
            throw new ApiRequestException(
                HttpStatus.CONFLICT, "현재 단계에서는 신창역 출발 처리가 필요하지 않습니다"
            );
        }
        DriverRepository.TripRow trip = repository.updateTripPhase(active.id(), "to_campus");
        return new DriverResponses.Phase(
            trip.id().toString(), id(trip.routeId()), trip.currentStopOrder(), trip.servicePhase(),
            trip.oneLoopOnly(), trip.scheduledEventAt(), trip.plannedDepartureAt(),
            trip.startedAt(), trip.updatedAt()
        );
    }

    @Transactional
    DriverResponses.Location location(JsonNode body, String rawDriverId) {
        double latitude = jsNumber(body == null ? null : body.get("lat"), Double.NaN);
        double longitude = jsNumber(body == null ? null : body.get("lng"), Double.NaN);
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude)
            || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
            throw badRequest("올바른 GPS 좌표가 필요합니다");
        }
        double rawSpeed = jsNumber(body == null ? null : body.get("speed"), 0);
        double speed = Double.isFinite(rawSpeed) ? Math.max(0, Math.min(55, rawSpeed)) : 0;
        double rawHeading = jsNumber(body == null ? null : body.get("heading"), 0);
        double heading = Double.isFinite(rawHeading) ? ((rawHeading % 360) + 360) % 360 : 0;

        UUID driverId = UUID.fromString(rawDriverId);
        DriverRepository.BusRow bus = repository.findRunningBus(driverId, false)
            .orElseThrow(() -> new ApiRequestException(HttpStatus.NOT_FOUND, "운행 중인 버스가 없습니다"));
        UUID tripId = repository.findActiveTripForBus(bus.id())
            .map(DriverRepository.TripRow::id).orElse(null);
        try {
            repository.recordLocation(
                bus.id(), tripId, latitude, longitude, speed, (int) Math.round(heading)
            );
        } catch (DataAccessException error) {
            throw new ApiRequestException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to save location");
        }
        return new DriverResponses.Location(bus.id(), latitude, longitude);
    }

    @Transactional
    DriverResponses.StopResult stop(String rawDriverId) {
        UUID driverId = UUID.fromString(rawDriverId);
        repository.lockDriver(driverId);
        DriverRepository.BusRow bus = repository.findRunningBus(driverId, true)
            .orElseThrow(() -> new ApiRequestException(HttpStatus.NOT_FOUND, "운행 중인 버스가 없습니다"));
        List<DriverRepository.TripRow> trips = repository.findActiveTripsForDriver(driverId, true);
        UUID restoreRouteId = trips.stream().map(DriverRepository.TripRow::originRouteId)
            .filter(java.util.Objects::nonNull).findFirst().orElse(null);
        repository.stopBus(bus.id(), restoreRouteId);
        repository.completeActiveTripsForDriver(driverId);
        return new DriverResponses.StopResult(bus.id());
    }

    @Transactional(readOnly = true)
    DriverResponses.Status status(String rawDriverId) {
        UUID driverId = UUID.fromString(rawDriverId);
        DriverResponses.Bus activeBus = repository.findRunningBus(driverId, false)
            .map(bus -> formatBus(bus, driverId)).orElse(null);
        return new DriverResponses.Status(activeBus);
    }

    private DriverRepository.TripRow activeTrip(UUID driverId) {
        return repository.findActiveTripsForDriver(driverId, true).stream().findFirst()
            .orElseThrow(() -> new ApiRequestException(HttpStatus.NOT_FOUND, "운행 중인 회차가 없습니다"));
    }

    private DriverResponses.Bus formatBus(DriverRepository.BusRow bus, UUID driverId) {
        DriverRepository.RouteRow route = repository.findRoute(bus.currentRouteId()).orElse(null);
        DriverRepository.TripRow trip = repository.findActiveTripForBus(bus.id()).orElse(null);
        return new DriverResponses.Bus(
            bus.id(), bus.name(), clientType(bus.type()), bus.capacity(), bus.status(), bus.running(),
            id(bus.currentDriverId()), id(bus.assignedDriverId()),
            driverId.equals(bus.assignedDriverId()), bus.assignedDriverId() == null,
            route == null ? null : formatRoute(route, repository.findStops(route.id())),
            trip == null ? null : new DriverResponses.ActiveTrip(
                trip.id().toString(), id(trip.routeId()), trip.status(), trip.currentStopOrder(),
                id(trip.originRouteId()), trip.servicePhase(), trip.scheduledEventAt(),
                trip.plannedDepartureAt(), trip.oneLoopOnly(), trip.startedAt(), trip.updatedAt()
            )
        );
    }

    private DriverResponses.Route formatRoute(
        DriverRepository.RouteRow route,
        List<DriverRepository.StopRow> stops
    ) {
        return new DriverResponses.Route(
            route.id().toString(), route.name(), clientType(route.type()), route.shuttleVariant(),
            route.color(), route.description(), route.region(), route.schedule(), route.scheduleBasis(),
            route.intervalMinutes(), route.departureOffsetMinutes(), route.boardingWaitMinutes(),
            id(route.continuationRouteId()), route.duration(), route.fare(),
            stops.stream().map(stop -> new DriverResponses.Stop(
                stop.id(), stop.name(), stop.order(), stop.lat(), stop.lng(), stop.arrivalTime()
            )).toList()
        );
    }

    private DriverResponses.Progress progressResponse(DriverRepository.TripRow trip) {
        return new DriverResponses.Progress(
            trip.id().toString(), id(trip.routeId()), trip.currentStopOrder(), trip.servicePhase(),
            trip.oneLoopOnly(), trip.startedAt(), trip.updatedAt()
        );
    }

    private ServiceTimes nextServiceTimes(DriverRepository.RouteRow route) {
        if (route == null) return new ServiceTimes(null, null);
        Instant now = clock.instant();
        ZonedDateTime localNow = now.atZone(SEOUL);
        if ("campus_loop".equals(route.shuttleVariant())) {
            int interval = Math.max(1, route.intervalMinutes() == null ? 10 : route.intervalMinutes());
            int currentMinutes = localNow.getHour() * 60 + localNow.getMinute();
            int nextMinutes = ((currentMinutes + interval - 1) / interval) * interval;
            ZonedDateTime departure = localNow.toLocalDate().atStartOfDay(SEOUL).plusMinutes(nextMinutes);
            if (departure.toInstant().isBefore(now)) departure = departure.plusMinutes(interval);
            OffsetDateTime value = departure.toOffsetDateTime();
            return new ServiceTimes(value, value);
        }

        int before = "campus_to_station".equals(route.shuttleVariant())
            ? Math.max(0, valueOr(route.departureOffsetMinutes(), 10)) : 0;
        int after = ("station_to_campus".equals(route.shuttleVariant())
            || "station_to_campus_loop".equals(route.shuttleVariant()))
            ? Math.max(0, valueOr(route.boardingWaitMinutes(), 5)) : 0;
        for (int dayOffset = 0; dayOffset <= 1; dayOffset++) {
            for (java.time.LocalTime time : parseSchedule(route.schedule())) {
                ZonedDateTime event = localNow.toLocalDate().plusDays(dayOffset).atTime(time).atZone(SEOUL);
                ZonedDateTime departure = event.plusMinutes(after - before);
                if (!departure.toInstant().isBefore(now)) {
                    return new ServiceTimes(event.toOffsetDateTime(), departure.toOffsetDateTime());
                }
            }
        }
        return new ServiceTimes(null, null);
    }

    private List<java.time.LocalTime> parseSchedule(String schedule) {
        if (schedule == null || schedule.isBlank()) return List.of();
        List<java.time.LocalTime> values = new ArrayList<>();
        for (String raw : schedule.split("[,\\n]")) {
            try {
                values.add(java.time.LocalTime.parse(raw.trim(), TIME));
            } catch (DateTimeParseException ignored) {
                // Edge ignores schedule fragments that are not valid 24-hour times.
            }
        }
        values.sort(Comparator.naturalOrder());
        return values;
    }

    private String initialServicePhase(String variant) {
        if ("campus_to_station".equals(variant)) return "to_station";
        if ("station_to_campus".equals(variant) || "station_to_campus_loop".equals(variant)) {
            return "waiting_station";
        }
        if ("campus_loop".equals(variant)) return "campus_loop";
        return "in_service";
    }

    private double jsNumber(JsonNode node, double fallback) {
        if (node == null || node.isMissingNode()) return fallback;
        if (node.isNull()) return 0;
        if (node.isBoolean()) return node.booleanValue() ? 1 : 0;
        if (node.isNumber()) return node.doubleValue();
        if (node.isString()) {
            String value = node.stringValue().trim();
            if (value.isEmpty()) return 0;
            try { return Double.parseDouble(value); }
            catch (NumberFormatException ignored) { return Double.NaN; }
        }
        return Double.NaN;
    }

    private String text(JsonNode body, String field) {
        if (body == null || !body.has(field) || body.get(field).isNull()) return "";
        return body.get(field).isString() ? body.get(field).stringValue() : body.get(field).toString();
    }

    private String clientType(String type) {
        if ("shuttle".equals(type)) return "campus";
        if ("commute".equals(type)) return "commuter";
        return type;
    }

    private String id(UUID value) {
        return value == null ? null : value.toString();
    }

    private int valueOr(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private int valueOrZero(Integer value) {
        return value == null ? 0 : value;
    }

    private ApiRequestException badRequest(String message) {
        return new ApiRequestException(HttpStatus.BAD_REQUEST, message);
    }

    private record ServiceTimes(OffsetDateTime scheduledEventAt, OffsetDateTime plannedDepartureAt) {
    }
}
