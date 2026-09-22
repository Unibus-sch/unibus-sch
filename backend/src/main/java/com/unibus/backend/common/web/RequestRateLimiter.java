package com.unibus.backend.common.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class RequestRateLimiter {

    private static final int MAX_BUCKETS = 2_000;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final Clock clock = Clock.systemUTC();
    private final JdbcTemplate jdbcTemplate;
    private volatile Boolean persistentLimiterAvailable;

    public RequestRateLimiter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void enforce(String action, String identity, int limit, int windowSeconds) {
        String key = hash(action + ":" + identity);
        if (hasPersistentLimiter()) {
            Boolean allowed = jdbcTemplate.queryForObject(
                "SELECT public.consume_api_rate_limit(?, ?, ?)",
                Boolean.class,
                key,
                limit,
                windowSeconds
            );
            if (!Boolean.TRUE.equals(allowed)) {
                throw new RateLimitExceededException(windowSeconds);
            }
            return;
        }

        Instant now = clock.instant();
        Bucket bucket = buckets.compute(key, (ignored, current) -> {
            if (current == null || !current.resetAt().isAfter(now)) {
                return new Bucket(1, now.plusSeconds(windowSeconds));
            }
            return new Bucket(current.count() + 1, current.resetAt());
        });

        if (buckets.size() > MAX_BUCKETS) {
            buckets.entrySet().removeIf(entry -> !entry.getValue().resetAt().isAfter(now));
        }
        if (bucket.count() > limit) {
            throw new RateLimitExceededException(windowSeconds);
        }
    }

    public void clear(String action, String identity) {
        String key = hash(action + ":" + identity);
        if (hasPersistentLimiter()) {
            jdbcTemplate.update("DELETE FROM public.api_rate_limits WHERE rate_key = ?", key);
        }
        buckets.remove(key);
    }

    private boolean hasPersistentLimiter() {
        Boolean known = persistentLimiterAvailable;
        if (known != null) {
            return known;
        }
        synchronized (this) {
            if (persistentLimiterAvailable == null) {
                persistentLimiterAvailable = Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                    "SELECT to_regprocedure('public.consume_api_rate_limit(text,integer,integer)') IS NOT NULL",
                    Boolean.class
                ));
            }
            return persistentLimiterAvailable;
        }
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private record Bucket(int count, Instant resetAt) {
    }
}
