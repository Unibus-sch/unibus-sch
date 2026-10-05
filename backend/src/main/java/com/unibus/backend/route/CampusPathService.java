package com.unibus.backend.route;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.unibus.backend.common.web.RequestRateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
class CampusPathService {
    private static final Logger log = LoggerFactory.getLogger(CampusPathService.class);
    private static final List<Map<String, Object>> DEFAULT_STOPS = List.of(
        stop("rear-gate", "후문", 36.772760, 126.933816),
        stop("hyang3", "향3", 36.768228, 126.935383),
        stop("hyang1", "향1", 36.767905, 126.932505),
        stop("library", "도서관", 36.768856, 126.931303),
        stop("main-gate", "정문", 36.769014, 126.927978));

    private final RouteRepository repository;
    private final NaverMapsClient naver;
    private final RequestRateLimiter limiter;
    private final ObjectMapper mapper;
    private MemoryPath memory;

    CampusPathService(RouteRepository repository, NaverMapsClient naver,
                      RequestRateLimiter limiter, ObjectMapper mapper) {
        this.repository = repository;
        this.naver = naver;
        this.limiter = limiter;
        this.mapper = mapper;
    }

    synchronized Map<String, Object> path(String identity) {
        UUID routeId = repository.findCampusRouteId().orElse(null);
        List<RouteResponse.Stop> stored = routeId == null ? List.of() : repository.findStops(routeId);
        // Edge checks the unfiltered row count before excluding missing coordinates.
        boolean storedRoute = stored.size() >= 2;
        List<RouteResponse.Stop> valid = stored.stream()
            .filter(stop -> stop.lat() != null && stop.lng() != null).toList();
        List<Map<String, Object>> visible = storedRoute && !valid.isEmpty()
            ? valid.stream().map(stop -> Map.<String, Object>of(
                "id", stop.id(), "name", stop.name(), "lat", stop.lat(), "lng", stop.lng(),
                "order", stop.order())).toList() : DEFAULT_STOPS;
        List<NaverMapsClient.Coordinate> points = new ArrayList<>();
        if (storedRoute && !valid.isEmpty()) {
            List<RouteResponse.ShapePoint> shapes = repository.findShapePoints(routeId);
            for (RouteResponse.Stop stop : valid) {
                points.add(new NaverMapsClient.Coordinate(stop.lat(), stop.lng()));
                shapes.stream().filter(shape -> shape.afterStopOrder().equals(stop.order()))
                    .forEach(shape -> points.add(new NaverMapsClient.Coordinate(shape.lat(), shape.lng())));
            }
        } else {
            for (Map<String, Object> stop : DEFAULT_STOPS) {
                if (stop.get("id").equals("main-gate")) {
                    points.add(new NaverMapsClient.Coordinate(36.768960, 126.929760));
                }
                points.add(coordinate(stop));
            }
        }
        String hash = hash(points);
        if (storedRoute) {
            var cached = repository.findCachedPath(routeId)
                .filter(value -> hash.equals(value.inputHash()) && value.path().size() > 1);
            if (cached.isPresent()) return response(cached.get().path(), visible, true);
        }
        if (memory != null && memory.hash().equals(hash) && memory.expiresAt().isAfter(Instant.now())) {
            return response(memory.path(), visible, true);
        }
        limiter.enforce("campus-path", identity, 30, 600);
        List<List<Double>> path = naver.directions(points).filter(value -> value.size() >= 2)
            .orElseGet(() -> fallback(visible));
        memory = new MemoryPath(hash, path, Instant.now().plusSeconds(1800));
        if (storedRoute) {
            try { repository.saveCachedPath(routeId, hash, path); }
            catch (DataAccessException error) { log.warn("Campus route cache write failed"); }
        }
        return response(path, visible, false);
    }

    private String hash(List<NaverMapsClient.Coordinate> points) {
        var input = points.stream().map(point -> List.of(
            String.format(Locale.ROOT, "%.7f", point.lng()),
            String.format(Locale.ROOT, "%.7f", point.lat()))).toList();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(mapper.writeValueAsString(input).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    static List<List<Double>> fallback(List<Map<String, Object>> stops) {
        List<List<Double>> path = new ArrayList<>();
        for (int index = 0; index < stops.size(); index++) {
            var from = coordinate(stops.get(index));
            var to = coordinate(stops.get((index + 1) % stops.size()));
            for (int step = 1; step <= 20; step++) {
                double ratio = step / 20.0;
                path.add(List.of(from.lng() + (to.lng() - from.lng()) * ratio,
                    from.lat() + (to.lat() - from.lat()) * ratio));
            }
        }
        return path;
    }

    private static NaverMapsClient.Coordinate coordinate(Map<String, Object> stop) {
        return new NaverMapsClient.Coordinate((double) stop.get("lat"), (double) stop.get("lng"));
    }

    private static Map<String, Object> stop(String id, String name, double lat, double lng) {
        return Map.of("id", id, "name", name, "lat", lat, "lng", lng);
    }

    private Map<String, Object> response(List<List<Double>> path, List<Map<String, Object>> stops, boolean cached) {
        return Map.of("path", path, "stops", stops, "cached", cached);
    }

    private record MemoryPath(String hash, List<List<Double>> path, Instant expiresAt) { }
}
