package com.unibus.backend.route;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.unibus.backend.common.api.ApiRequestException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
class RouteAdminService {

    private static final Set<String> SHUTTLE_VARIANTS = Set.of(
        "campus_loop", "campus_to_station", "station_to_campus", "station_to_campus_loop"
    );
    private static final Pattern TIME = Pattern.compile("^([01]?\\d|2[0-3]):[0-5]\\d$");
    private static final Pattern STATION = Pattern.compile("신창|순천향대역|순천향대학교역");
    private final RouteAdminRepository repository;
    private final NaverMapsClient naverMapsClient;
    private final ObjectMapper objectMapper;

    RouteAdminService(RouteAdminRepository repository, NaverMapsClient naverMapsClient, ObjectMapper objectMapper) {
        this.repository = repository;
        this.naverMapsClient = naverMapsClient;
        this.objectMapper = objectMapper;
    }

    @Transactional
    Map<String, Object> create(JsonNode body) {
        String name = string(body, "name");
        String type = string(body, "type");
        if (blank(name) || blank(type)) throw badRequest("Missing required fields");
        String variant = nullableString(body, "shuttleVariant");
        validateDetails(type, variant, body.get("stops"), body.get("shapePoints"));
        validateServiceRules(type, variant, body, true);

        Map<String, Object> values = baseValues(body, type, variant);
        values.put("name", name);
        RouteAdminRepository.RawRoute route = repository.insert(values);
        replaceDetails(route.id(), body);
        return response(route, true);
    }

    @Transactional
    Map<String, Object> update(String rawId, JsonNode body) {
        UUID id = uuid(rawId);
        RouteAdminRepository.RawRoute existing = id == null ? null : repository.find(id).orElse(null);
        if (existing == null) throw new ApiRequestException(HttpStatus.NOT_FOUND, "Route not found");
        String type = has(body, "type") ? string(body, "type") : clientType(existing.type());
        String variant = has(body, "shuttleVariant")
            ? nullableString(body, "shuttleVariant") : existing.shuttleVariant();
        validateDetails(type, variant, body.get("stops"), body.get("shapePoints"));
        validateServiceRules(type, variant, body, false);

        Map<String, Object> updates = new LinkedHashMap<>();
        copyText(body, updates, "name", "name", false);
        if (has(body, "type")) updates.put("type", dbType(type));
        if (has(body, "type") || has(body, "shuttleVariant")) {
            updates.put("shuttle_variant", normalizeVariant(type, variant));
        }
        copyNullable(body, updates, "description", "description");
        copyText(body, updates, "color", "color", false);
        copyNullable(body, updates, "region", "region");
        copyNullable(body, updates, "schedule", "schedule");
        copyNullable(body, updates, "duration", "duration");
        copyNullable(body, updates, "fare", "fare");
        if (has(body, "isActive")) updates.put("is_active", body.get("isActive").booleanValue());
        if (hasAnyServiceRule(body) || has(body, "type") || has(body, "shuttleVariant")) {
            updates.putAll(serviceRuleValues(type, variant, body));
        }
        RouteAdminRepository.RawRoute updated = repository.update(id, updates);
        replaceDetails(id, body);
        return response(updated, false);
    }

    @Transactional
    void delete(String rawId) {
        UUID id = uuid(rawId);
        if (id == null || !repository.delete(id)) {
            throw new ApiRequestException(HttpStatus.NOT_FOUND, "Route not found");
        }
    }

    Map<String, Object> preview(JsonNode body) {
        List<NaverMapsClient.Coordinate> points = directionPoints(body);
        List<List<Double>> path = naverMapsClient.directions(points)
            .orElseGet(() -> points.stream().map(point -> List.of(point.lng(), point.lat())).toList());
        return Map.of("path", path);
    }

    private Map<String, Object> baseValues(JsonNode body, String type, String variant) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("type", dbType(type));
        values.put("shuttle_variant", normalizeVariant(type, variant));
        values.put("description", nullableString(body, "description"));
        values.put("color", defaultText(body, "color", "#1E3B8A"));
        values.put("region", nullableString(body, "region"));
        values.put("schedule", nullableString(body, "schedule"));
        values.putAll(serviceRuleValues(type, variant, body));
        values.put("duration", nullableString(body, "duration"));
        values.put("fare", nullableString(body, "fare"));
        values.put("is_active", !has(body, "isActive") || body.get("isActive").booleanValue());
        return values;
    }

    private Map<String, Object> serviceRuleValues(String type, String variant, JsonNode body) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (!"shuttle".equals(dbType(type))) {
            values.put("schedule_basis", nullableString(body, "scheduleBasis"));
            values.put("interval_minutes", nullableInteger(body, "intervalMinutes"));
            values.put("departure_offset_minutes", integer(body, "departureOffsetMinutes", 0));
            values.put("boarding_wait_minutes", integer(body, "boardingWaitMinutes", 0));
            values.put("continuation_route_id", nullableString(body, "continuationRouteId"));
            return values;
        }
        String normalized = normalizeVariant(type, variant);
        values.put("schedule_basis", switch (normalized) {
            case "campus_to_station" -> "train_departure";
            case "campus_loop" -> "bus_departure";
            default -> "train_arrival";
        });
        values.put("interval_minutes", "campus_loop".equals(normalized)
            ? bounded(body, "intervalMinutes", 10, 1, 180) : null);
        values.put("departure_offset_minutes", "campus_to_station".equals(normalized)
            ? bounded(body, "departureOffsetMinutes", 10, 0, 120) : 0);
        values.put("boarding_wait_minutes",
            Set.of("station_to_campus", "station_to_campus_loop").contains(normalized)
                ? bounded(body, "boardingWaitMinutes", 5, 0, 120) : 0);
        values.put("continuation_route_id", "station_to_campus_loop".equals(normalized)
            ? nullableString(body, "continuationRouteId") : null);
        return values;
    }

    private void validateDetails(String type, String variant, JsonNode stops, JsonNode shapes) {
        List<String> errors = new ArrayList<>();
        if (stops != null && !stops.isMissingNode()) {
            if (!stops.isArray()) {
                errors.add("정류장 데이터 형식이 올바르지 않습니다.");
            } else {
                List<String> names = new ArrayList<>();
                stops.forEach(stop -> {
                    String name = string(stop, "name");
                    if (!blank(name)) names.add(name.trim());
                });
                if (names.size() < 2) errors.add("정류장은 최소 2개 이상 필요합니다.");
                List<String> normalized = names.stream()
                    .map(name -> name.replaceAll("\\s+", "").toLowerCase(Locale.ROOT)).toList();
                for (int index = 0; index < normalized.size(); index++) {
                    int first = normalized.indexOf(normalized.get(index));
                    boolean closedLoop = "shuttle".equals(dbType(type)) && "campus_loop".equals(variant)
                        && first == 0 && index == normalized.size() - 1;
                    if (first != index && !closedLoop) {
                        errors.add("중복된 정류장이 있습니다: " + names.get(index));
                        break;
                    }
                }
                if ("shuttle".equals(dbType(type)) && variant != null && !"campus_loop".equals(variant)
                    && names.stream().noneMatch(name -> STATION.matcher(name).find())) {
                    errors.add("신창역 셔틀은 정류장에 신창역 또는 순천향대역이 포함되어야 합니다.");
                }
            }
        }
        if (shapes != null && !shapes.isMissingNode()) {
            if (!shapes.isArray()) errors.add("경로 보정점 데이터 형식이 올바르지 않습니다.");
            else for (JsonNode point : shapes) {
                if (!finite(point.get("lat")) || !finite(point.get("lng"))) {
                    errors.add("좌표가 잘못된 경로 보정점이 있습니다.");
                    break;
                }
            }
        }
        if (!errors.isEmpty()) throw badRequest(String.join(" ", errors));
    }

    private void validateServiceRules(String type, String variant, JsonNode body, boolean requireSchedule) {
        if (!"shuttle".equals(dbType(type))) return;
        List<String> errors = new ArrayList<>();
        String schedule = defaultText(body, "schedule", "");
        List<String> tokens = Pattern.compile("[,\\n]").splitAsStream(schedule)
            .map(String::trim).filter(value -> !value.isEmpty()).toList();
        if (has(body, "schedule") && tokens.stream().anyMatch(value -> !TIME.matcher(value).matches())) {
            errors.add("시간표는 08:20과 같은 24시간 형식으로 입력해 주세요.");
        }
        if (requireSchedule && !"campus_loop".equals(normalizeVariant(type, variant)) && tokens.isEmpty()) {
            errors.add("신창역 셔틀은 기준이 되는 지하철 도착 또는 출발 시각이 필요합니다.");
        }
        checkRange(body, "intervalMinutes", 1, 180, "출발 간격", errors);
        checkRange(body, "departureOffsetMinutes", 0, 120, "선출발 시간", errors);
        checkRange(body, "boardingWaitMinutes", 0, 120, "탑승 대기 시간", errors);
        if (!errors.isEmpty()) throw badRequest(String.join(" ", errors));
    }

    private void replaceDetails(UUID routeId, JsonNode body) {
        boolean stops = has(body, "stops");
        boolean shapes = has(body, "shapePoints");
        if (!stops && !shapes) return;
        try {
            repository.replaceDetails(
                routeId, stops, stops ? objectMapper.writeValueAsString(body.get("stops")) : "[]",
                shapes, shapes ? objectMapper.writeValueAsString(body.get("shapePoints")) : "[]"
            );
        } catch (JacksonException error) {
            throw new IllegalStateException("Failed to encode route details", error);
        }
    }

    private List<NaverMapsClient.Coordinate> directionPoints(JsonNode body) {
        List<NaverMapsClient.Coordinate> result = new ArrayList<>();
        JsonNode stops = body == null ? null : body.get("stops");
        JsonNode shapes = body == null ? null : body.get("shapePoints");
        if (stops == null || !stops.isArray()) return result;
        for (int index = 0; index < stops.size(); index++) {
            JsonNode stop = stops.get(index);
            if (!finite(stop.get("lat")) || !finite(stop.get("lng"))) continue;
            result.add(new NaverMapsClient.Coordinate(stop.get("lat").asDouble(), stop.get("lng").asDouble()));
            if (shapes != null && shapes.isArray()) {
                int order = stop.path("order").asInt(index + 1);
                for (JsonNode shape : shapes) {
                    int after = shape.has("afterStopOrder") ? shape.path("afterStopOrder").asInt()
                        : shape.path("after_stop_order").asInt(1);
                    if (after == order && finite(shape.get("lat")) && finite(shape.get("lng"))) {
                        result.add(new NaverMapsClient.Coordinate(
                            shape.get("lat").asDouble(), shape.get("lng").asDouble()
                        ));
                    }
                }
            }
        }
        return result;
    }

    private Map<String, Object> response(RouteAdminRepository.RawRoute route, boolean created) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", route.id().toString());
        result.put("name", route.name());
        result.put("type", clientType(route.type()));
        result.put("shuttleVariant", route.shuttleVariant());
        result.put("description", route.description());
        result.put("color", route.color());
        result.put("region", route.region());
        result.put("schedule", route.schedule());
        result.put("scheduleBasis", route.scheduleBasis());
        result.put("intervalMinutes", route.intervalMinutes());
        result.put("departureOffsetMinutes", route.departureOffsetMinutes());
        result.put("boardingWaitMinutes", route.boardingWaitMinutes());
        result.put("continuationRouteId", route.continuationRouteId());
        result.put("duration", route.duration());
        result.put("fare", route.fare());
        result.put("isActive", route.active());
        result.put(created ? "createdAt" : "updatedAt", created ? route.createdAt() : route.updatedAt());
        return result;
    }

    private String dbType(String type) {
        return "campus".equals(type) ? "shuttle" : "commuter".equals(type) ? "commute" : type;
    }

    private String clientType(String type) {
        return "shuttle".equals(type) ? "campus" : "commute".equals(type) ? "commuter" : type;
    }

    private String normalizeVariant(String type, String variant) {
        if (!"shuttle".equals(dbType(type))) return null;
        return variant != null && SHUTTLE_VARIANTS.contains(variant) ? variant : "campus_loop";
    }

    private void checkRange(JsonNode body, String field, int min, int max, String label, List<String> errors) {
        if (!has(body, field) || body.get(field).isNull()) return;
        double value = body.get(field).asDouble(Double.NaN);
        if (!Double.isFinite(value) || value < min || value > max) {
            errors.add(label + "은 " + min + "~" + max + "분으로 입력해 주세요.");
        }
    }

    private int bounded(JsonNode body, String field, int fallback, int min, int max) {
        if (!has(body, field) || !body.get(field).isNumber()) return fallback;
        return Math.max(min, Math.min(max, body.get(field).asInt()));
    }

    private Integer nullableInteger(JsonNode body, String field) {
        return has(body, field) && !body.get(field).isNull() ? body.get(field).asInt() : null;
    }

    private int integer(JsonNode body, String field, int fallback) {
        return has(body, field) && !body.get(field).isNull() ? body.get(field).asInt() : fallback;
    }

    private void copyText(JsonNode body, Map<String, Object> target, String input, String column, boolean nullable) {
        if (!has(body, input)) return;
        String value = nullableString(body, input);
        if (nullable || !blank(value)) target.put(column, value);
    }

    private void copyNullable(JsonNode body, Map<String, Object> target, String input, String column) {
        if (has(body, input)) target.put(column, nullableString(body, input));
    }

    private boolean hasAnyServiceRule(JsonNode body) {
        return has(body, "scheduleBasis") || has(body, "intervalMinutes")
            || has(body, "departureOffsetMinutes") || has(body, "boardingWaitMinutes")
            || has(body, "continuationRouteId");
    }

    private boolean finite(JsonNode node) {
        return node != null && node.isNumber() && Double.isFinite(node.asDouble());
    }

    private boolean has(JsonNode body, String field) {
        return body != null && body.has(field);
    }

    private String string(JsonNode body, String field) {
        return has(body, field) && body.get(field).isString() ? body.get(field).stringValue() : null;
    }

    private String nullableString(JsonNode body, String field) {
        String value = string(body, field);
        return blank(value) ? null : value;
    }

    private String defaultText(JsonNode body, String field, String fallback) {
        String value = string(body, field);
        return blank(value) ? fallback : value;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private UUID uuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    private ApiRequestException badRequest(String message) {
        return new ApiRequestException(HttpStatus.BAD_REQUEST, message);
    }
}
