package com.unibus.backend.bus;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import com.unibus.backend.common.api.ApiRequestException;
import com.unibus.backend.common.api.EdgeJson;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Service
class LegacyBusLocationService {
    private final JdbcTemplate jdbc;
    LegacyBusLocationService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    Map<String, Object> update(String busId, JsonNode body, String userId, String role) {
        if (body == null || !body.has("lat") || !body.has("lng")) throw badRequest("Missing required fields");
        double lat = EdgeJson.number(body.get("lat")), lng = EdgeJson.number(body.get("lng"));
        double speed = body.has("speed") ? EdgeJson.number(body.get("speed")) : 0;
        double heading = body.has("heading") ? EdgeJson.number(body.get("heading")) : 0;
        if (!Double.isFinite(lat) || lat < -90 || lat > 90 || !Double.isFinite(lng) || lng < -180 || lng > 180
            || !Double.isFinite(speed) || speed < 0 || speed > 250 || !Double.isFinite(heading) || heading < 0 || heading >= 360) {
            throw badRequest("Invalid location data");
        }
        // Serialize with start/stop on the same bus so ownership cannot change during the write.
        var buses = jdbc.query("SELECT current_driver_id, is_running FROM buses WHERE id = ? FOR UPDATE",
            (rs, index) -> new Owner(rs.getObject("current_driver_id", UUID.class), rs.getBoolean("is_running")), busId);
        if (buses.isEmpty()) throw new ApiRequestException(HttpStatus.NOT_FOUND, "Bus not found");
        var owner = buses.getFirst();
        if ("driver".equals(role) && (!owner.running() || !UUID.fromString(userId).equals(owner.driver()))) {
            throw new ApiRequestException(HttpStatus.FORBIDDEN,
                "Forbidden: You can only update your currently assigned bus location");
        }
        int roundedHeading = (int) Math.floor(heading + 0.5);
        return jdbc.queryForObject("""
            INSERT INTO bus_locations (bus_id, latitude, longitude, speed, heading)
            VALUES (?, ?, ?, ?, ?) RETURNING bus_id, latitude, longitude, speed, heading, timestamp
            """, (rs, index) -> Map.<String, Object>of(
                "busId", rs.getString("bus_id"), "lat", rs.getDouble("latitude"), "lng", rs.getDouble("longitude"),
                "speed", rs.getDouble("speed"), "heading", rs.getInt("heading"),
                "timestamp", rs.getObject("timestamp", OffsetDateTime.class)), busId, lat, lng, speed, roundedHeading);
        // Legacy Edge writes history only. /driver/location remains the live-state RPC endpoint.
    }

    private ApiRequestException badRequest(String message) { return new ApiRequestException(HttpStatus.BAD_REQUEST, message); }
    private record Owner(UUID driver, boolean running) { }
}
