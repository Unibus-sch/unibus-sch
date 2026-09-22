package com.unibus.backend.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class DatabaseSchemaValidator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DatabaseSchemaValidator.class);
    private static final String REQUIRED_MIGRATION = "20260918000000_integrate_realtime_storage_push.sql";

    static final Map<String, List<String>> REQUIRED_COLUMNS = requiredColumns();
    static final List<String> REQUIRED_ROUTINES = List.of(
        "consume_api_rate_limit(text,integer,integer)",
        "replace_route_details(uuid,boolean,jsonb,boolean,jsonb)",
        "record_bus_location(character varying,uuid,numeric,numeric,numeric,integer)",
        "admin_force_stop_bus(character varying)"
    );

    private final JdbcTemplate jdbcTemplate;
    private final SchemaValidationProperties properties;

    public DatabaseSchemaValidator(
        JdbcTemplate jdbcTemplate,
        SchemaValidationProperties properties
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        if (!properties.isEnabled()) {
            log.warn("Database schema startup validation is disabled");
            return;
        }
        validate();
    }

    void validate() {
        Map<String, Set<String>> actual = new TreeMap<>();
        jdbcTemplate.query("""
            SELECT table_name, column_name
            FROM information_schema.columns
            WHERE table_schema = 'public'
            """, resultSet -> {
                actual.computeIfAbsent(resultSet.getString("table_name"), ignored -> new LinkedHashSet<>())
                    .add(resultSet.getString("column_name"));
            });

        List<String> missing = new ArrayList<>();
        REQUIRED_COLUMNS.forEach((table, columns) -> {
            Set<String> actualColumns = actual.get(table);
            if (actualColumns == null) {
                missing.add("table public." + table);
                return;
            }
            columns.stream()
                .filter(column -> !actualColumns.contains(column))
                .map(column -> "column public." + table + "." + column)
                .forEach(missing::add);
        });
        for (String routine : REQUIRED_ROUTINES) {
            Boolean present = jdbcTemplate.queryForObject(
                "SELECT to_regprocedure(?) IS NOT NULL",
                Boolean.class,
                "public." + routine
            );
            if (!Boolean.TRUE.equals(present)) {
                missing.add("routine public." + routine);
            }
        }

        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                "Database schema validation failed; missing " + String.join(", ", missing)
                    + ". Apply Supabase migrations through " + REQUIRED_MIGRATION
                    + " before starting Spring. No schema changes were attempted."
            );
        }
        log.info("Database schema validation passed for {} tables and {} routines",
            REQUIRED_COLUMNS.size(), REQUIRED_ROUTINES.size());
    }

    private static Map<String, List<String>> requiredColumns() {
        Map<String, List<String>> required = new LinkedHashMap<>();
        required.put("users", List.of(
            "id", "email", "password_hash", "name", "student_id", "role", "provider",
            "provider_id", "profile_image", "created_at", "updated_at"
        ));
        required.put("auth_tokens", List.of("user_id", "token", "expires_at"));
        required.put("api_rate_limits", List.of(
            "rate_key", "request_count", "window_started_at", "updated_at"
        ));
        required.put("notices", List.of(
            "id", "title", "content", "category", "priority", "author_id", "is_pinned",
            "view_count", "image_urls", "content_below", "created_at", "updated_at"
        ));
        required.put("routes", List.of(
            "id", "name", "type", "description", "shuttle_variant", "color", "region",
            "schedule", "schedule_basis", "interval_minutes", "departure_offset_minutes",
            "boarding_wait_minutes", "continuation_route_id", "duration", "fare", "is_active",
            "created_at", "updated_at"
        ));
        required.put("route_stops", List.of(
            "id", "route_id", "stop_name", "stop_order", "latitude", "longitude", "arrival_time"
        ));
        required.put("route_shape_points", List.of(
            "id", "route_id", "name", "after_stop_order", "point_order", "latitude", "longitude"
        ));
        required.put("route_path_cache", List.of("route_id", "input_hash", "path", "generated_at"));
        required.put("buses", List.of(
            "id", "name", "type", "license_plate", "current_driver_id", "assigned_driver_id",
            "capacity", "status", "is_running", "current_route_id", "created_at", "updated_at"
        ));
        required.put("bus_trips", List.of(
            "id", "bus_id", "route_id", "origin_route_id", "driver_id", "status",
            "current_stop_order", "service_phase", "scheduled_event_at", "planned_departure_at",
            "one_loop_only", "started_at", "ended_at", "updated_at"
        ));
        required.put("bus_latest_state", List.of(
            "bus_id", "trip_id", "latitude", "longitude", "speed", "heading", "timestamp",
            "last_history_at"
        ));
        required.put("bus_locations", List.of(
            "id", "bus_id", "latitude", "longitude", "speed", "heading", "timestamp"
        ));
        required.put("push_subscriptions", List.of(
            "id", "user_id", "endpoint", "p256dh", "auth", "user_agent", "enabled",
            "last_error", "created_at", "updated_at"
        ));
        required.put("notification_deliveries", List.of(
            "id", "notice_id", "target", "attempted", "sent", "failed", "created_by", "created_at"
        ));
        required.put("user_reports", List.of(
            "id", "user_id", "category", "title", "details", "status", "related_bus_id",
            "related_route_id", "admin_note", "resolved_at", "created_at", "updated_at"
        ));
        required.put("admin_action_logs", List.of(
            "id", "admin_id", "action", "target_type", "target_id", "metadata", "created_at"
        ));
        return Map.copyOf(required);
    }
}
