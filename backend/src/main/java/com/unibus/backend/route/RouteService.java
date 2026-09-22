package com.unibus.backend.route;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.unibus.backend.common.web.RequestRateLimiter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
class RouteService {

    private final RouteRepository routeRepository;
    private final NaverMapsClient naverMapsClient;
    private final RequestRateLimiter requestRateLimiter;
    private final ObjectMapper objectMapper;

    RouteService(
        RouteRepository routeRepository,
        NaverMapsClient naverMapsClient,
        RequestRateLimiter requestRateLimiter,
        ObjectMapper objectMapper
    ) {
        this.routeRepository = routeRepository;
        this.naverMapsClient = naverMapsClient;
        this.requestRateLimiter = requestRateLimiter;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    List<RouteResponse> findAll() {
        return routeRepository.findAll();
    }

    @Transactional(readOnly = true)
    Optional<RouteResponse> findById(String rawId) {
        UUID id = parseUuid(rawId);
        return id == null ? Optional.empty() : routeRepository.findById(id);
    }

    @Transactional
    Optional<Map<String, Object>> findPath(String rawId, String requestIdentity) {
        UUID routeId = parseUuid(rawId);
        if (routeId == null) {
            return Optional.empty();
        }

        List<RouteResponse.Stop> stops = new ArrayList<>(routeRepository.findStops(routeId));
        if (stops.isEmpty()) {
            return Optional.empty();
        }

        if (stops.stream().anyMatch(this::hasMissingCoordinates)) {
            requestRateLimiter.enforce("route-geocode", requestIdentity, 10, 3_600);
            if (naverMapsClient.isConfigured()) {
                stops = stops.stream().map(this::resolveCoordinates).toList();
            }
        }

        List<RouteResponse.ShapePoint> shapePoints = routeRepository.findShapePoints(routeId);
        List<RouteResponse.Stop> validStops = stops.stream()
            .filter(stop -> !hasMissingCoordinates(stop))
            .toList();

        Map<String, Object> data = new LinkedHashMap<>();
        // Edge's route-path endpoint deliberately returns only marker fields;
        // arrivalTime belongs to route detail responses and must not appear here as null.
        data.put("stops", stops.stream().map(PathStop::from).toList());
        data.put("shapePoints", shapePoints);

        if (validStops.isEmpty()) {
            data.put("path", List.of());
            return Optional.of(data);
        }

        List<NaverMapsClient.Coordinate> points = buildDirectionPoints(validStops, shapePoints);
        String inputHash = inputHash(points);
        Optional<RouteRepository.CachedPath> cachedPath = routeRepository.findCachedPath(routeId)
            .filter(cached -> cached.inputHash().equals(inputHash))
            .filter(cached -> cached.path().size() > 1);
        if (cachedPath.isPresent()) {
            data.put("path", cachedPath.orElseThrow().path());
            data.put("cached", true);
            return Optional.of(data);
        }

        requestRateLimiter.enforce("route-directions", requestIdentity, 30, 600);
        List<List<Double>> path = naverMapsClient.directions(points)
            .orElseGet(() -> points.stream().map(point -> List.of(point.lng(), point.lat())).toList());
        routeRepository.saveCachedPath(routeId, inputHash, path);
        data.put("path", path);
        data.put("cached", false);
        return Optional.of(data);
    }

    private RouteResponse.Stop resolveCoordinates(RouteResponse.Stop stop) {
        if (!hasMissingCoordinates(stop)) {
            return stop;
        }
        Optional<NaverMapsClient.Coordinate> coordinate = naverMapsClient.geocode(stop.name());
        if (coordinate.isEmpty()) {
            return stop;
        }
        NaverMapsClient.Coordinate resolved = coordinate.orElseThrow();
        routeRepository.updateStopCoordinates(UUID.fromString(stop.id()), resolved.lat(), resolved.lng());
        return new RouteResponse.Stop(
            stop.id(), stop.name(), stop.order(), resolved.lat(), resolved.lng(), stop.arrivalTime()
        );
    }

    private List<NaverMapsClient.Coordinate> buildDirectionPoints(
        List<RouteResponse.Stop> stops,
        List<RouteResponse.ShapePoint> shapePoints
    ) {
        List<NaverMapsClient.Coordinate> points = new ArrayList<>();
        for (RouteResponse.Stop stop : stops) {
            points.add(new NaverMapsClient.Coordinate(stop.lat(), stop.lng()));
            shapePoints.stream()
                .filter(point -> point.afterStopOrder().equals(stop.order()))
                .forEach(point -> points.add(new NaverMapsClient.Coordinate(point.lat(), point.lng())));
        }
        return points;
    }

    private String inputHash(List<NaverMapsClient.Coordinate> points) {
        List<List<String>> input = points.stream()
            .map(point -> List.of(fixed(point.lng()), fixed(point.lat())))
            .toList();
        try {
            byte[] json = objectMapper.writeValueAsString(input).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
        } catch (JacksonException | NoSuchAlgorithmException error) {
            throw new IllegalStateException("Failed to hash route path input", error);
        }
    }

    private String fixed(double value) {
        return String.format(Locale.ROOT, "%.7f", value);
    }

    private boolean hasMissingCoordinates(RouteResponse.Stop stop) {
        return stop.lat() == null || stop.lng() == null;
    }

    private record PathStop(String id, String name, Integer order, Double lat, Double lng) {
        private static PathStop from(RouteResponse.Stop stop) {
            return new PathStop(stop.id(), stop.name(), stop.order(), stop.lat(), stop.lng());
        }
    }

    private UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
