package com.unibus.backend.bus;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.unibus.backend.common.api.ApiRequestException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
class BusAdminService {

    private static final Set<String> TYPES = Set.of("shuttle", "commute");
    private static final Set<String> STATUSES = Set.of("active", "inactive", "maintenance");
    private static final Pattern UUID_PATTERN = Pattern.compile(
        "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
        Pattern.CASE_INSENSITIVE
    );
    private final BusAdminRepository repository;
    private final ObjectMapper objectMapper;

    BusAdminService(BusAdminRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    Map<String, Object> create(JsonNode body) {
        String name = string(body, "name");
        String type = string(body, "type");
        name = name == null ? "" : name.trim();
        if (name.isEmpty() || type == null || type.isEmpty()) {
            throw badRequest("Missing required fields: name, type");
        }
        if (name.length() > 100) throw badRequest("Bus name must be 100 characters or fewer");
        String dbType = dbType(type);
        if (!TYPES.contains(dbType)) throw badRequest("Invalid bus type");
        int capacity = number(body, "capacity", 45);
        if (capacity < 1 || capacity > 100 || !integerNode(body, "capacity")) {
            throw badRequest("Capacity must be an integer between 1 and 100");
        }
        String plate = string(body, "licensePlate");
        plate = plate == null ? "" : plate.trim();
        if (plate.length() > 30) throw badRequest("License plate must be 30 characters or fewer");
        String rawRouteId = string(body, "routeId");
        if (rawRouteId != null && !rawRouteId.isEmpty() && !UUID_PATTERN.matcher(rawRouteId).matches()) {
            throw badRequest("Invalid route ID");
        }
        String prefix = "shuttle".equals(dbType) ? "SH" : "CM";
        String id = prefix + "-" + UUID.randomUUID().toString().replace("-", "")
            .substring(0, 10).toUpperCase();
        BusAdminRepository.RawBus bus = repository.insert(
            id, name, dbType, capacity, plate.isEmpty() ? null : plate,
            rawRouteId == null || rawRouteId.isEmpty() ? null : UUID.fromString(rawRouteId)
        );
        return response(bus, true);
    }

    @Transactional
    Map<String, Object> update(String id, JsonNode body) {
        if (repository.find(id).isEmpty()) throw new ApiRequestException(HttpStatus.NOT_FOUND, "Bus not found");
        Map<String, Object> updates = new LinkedHashMap<>();
        if (has(body, "name")) {
            String name = String.valueOf(value(body, "name")).trim();
            if (name.isEmpty() || name.length() > 100) throw badRequest("Bus name is required");
            updates.put("name", name);
        }
        if (has(body, "type")) {
            String type = dbType(String.valueOf(value(body, "type")));
            if (!TYPES.contains(type)) throw badRequest("Invalid bus type");
            updates.put("type", type);
        }
        if (has(body, "capacity")) {
            JsonNode node = body.get("capacity");
            if (!node.isIntegralNumber() || node.asInt() < 1 || node.asInt() > 100) {
                throw badRequest("Capacity must be an integer between 1 and 100");
            }
            updates.put("capacity", node.asInt());
        }
        if (has(body, "licensePlate")) {
            String plate = body.get("licensePlate").isNull() ? "" : body.get("licensePlate").asString("").trim();
            if (plate.length() > 30) throw badRequest("Invalid license plate");
            updates.put("license_plate", plate.isEmpty() ? null : plate);
        }
        if (has(body, "status")) {
            String status = body.get("status").asString();
            if (!STATUSES.contains(status)) throw badRequest("Invalid bus status");
            updates.put("status", status);
        }
        if (has(body, "currentRouteId")) {
            String routeId = nullableString(body.get("currentRouteId"));
            if (routeId != null && !UUID_PATTERN.matcher(routeId).matches()) throw badRequest("Invalid route ID");
            updates.put("current_route_id", routeId == null ? null : UUID.fromString(routeId));
        }
        if (has(body, "assignedDriverId")) {
            String driverId = nullableString(body.get("assignedDriverId"));
            if (driverId == null) {
                updates.put("assigned_driver_id", null);
            } else {
                UUID idValue;
                try {
                    idValue = UUID.fromString(driverId);
                } catch (IllegalArgumentException error) {
                    throw badRequest("선택한 사용자는 버스 기사가 아닙니다");
                }
                if (!repository.isDriver(idValue)) throw badRequest("선택한 사용자는 버스 기사가 아닙니다");
                updates.put("assigned_driver_id", idValue);
            }
        }
        if (has(body, "isRunning") && !body.get("isRunning").asBoolean(true)) {
            updates.put("is_running", false);
            updates.put("current_driver_id", null);
        }
        if (updates.isEmpty()) throw badRequest("No valid updates provided");
        return response(repository.update(id, updates), false);
    }

    @Transactional
    void forceStop(String id, String adminId, JsonNode body) {
        if (repository.find(id).isEmpty()) throw new ApiRequestException(HttpStatus.NOT_FOUND, "Bus not found");
        repository.forceStop(id);
        String reason = string(body, "reason");
        if (reason == null) reason = "관리자 강제 종료";
        if (reason.length() > 300) reason = reason.substring(0, 300);
        try {
            repository.log(UUID.fromString(adminId), "bus_force_stopped", id,
                objectMapper.writeValueAsString(Map.of("reason", reason)));
        } catch (tools.jackson.core.JacksonException error) {
            throw new IllegalStateException(error);
        }
    }

    @Transactional
    void delete(String id) {
        BusAdminRepository.RawBus bus = repository.find(id)
            .orElseThrow(() -> new ApiRequestException(HttpStatus.NOT_FOUND, "Bus not found"));
        if ("active".equals(bus.status())) {
            throw new ApiRequestException(HttpStatus.CONFLICT, "Cannot delete an active bus. Stop the bus first.");
        }
        repository.delete(id);
    }

    private Map<String, Object> response(BusAdminRepository.RawBus bus, boolean created) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", bus.id()); data.put("name", bus.name()); data.put("type", clientType(bus.type()));
        data.put("capacity", bus.capacity()); data.put("licensePlate", bus.licensePlate());
        data.put("status", bus.status());
        if (!created) {
            data.put("isRunning", bus.running()); data.put("currentDriverId", bus.currentDriverId());
            data.put("assignedDriverId", bus.assignedDriverId());
        }
        data.put("currentRouteId", bus.currentRouteId());
        data.put(created ? "createdAt" : "updatedAt", created ? bus.createdAt() : bus.updatedAt());
        return data;
    }

    private String dbType(String type) {
        return "campus".equals(type) ? "shuttle" : Set.of("commuter", "direct").contains(type) ? "commute" : type;
    }

    private String clientType(String type) {
        return "shuttle".equals(type) ? "campus" : "commute".equals(type) ? "commuter" : type;
    }

    private boolean has(JsonNode body, String field) { return body != null && body.has(field); }
    private String string(JsonNode body, String field) {
        return has(body, field) && body.get(field).isString() ? body.get(field).stringValue() : null;
    }
    private Object value(JsonNode body, String field) {
        JsonNode node = body.get(field);
        return node.isString() ? node.stringValue() : node.toString();
    }
    private String nullableString(JsonNode node) {
        if (node == null || node.isNull()) return null;
        String value = node.asString("").trim();
        return value.isEmpty() ? null : value;
    }
    private int number(JsonNode body, String field, int fallback) {
        return !has(body, field) || body.get(field).isNull() || body.get(field).asString().isEmpty()
            ? fallback : body.get(field).asInt(Integer.MIN_VALUE);
    }
    private boolean integerNode(JsonNode body, String field) {
        if (!has(body, field) || body.get(field).isNull() || body.get(field).asString().isEmpty()) return true;
        double value = body.get(field).asDouble(Double.NaN);
        return Double.isFinite(value) && value == Math.rint(value);
    }
    private ApiRequestException badRequest(String message) {
        return new ApiRequestException(HttpStatus.BAD_REQUEST, message);
    }
}
