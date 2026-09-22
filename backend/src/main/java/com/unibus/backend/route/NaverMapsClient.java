package com.unibus.backend.route;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import tools.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

@Component
class NaverMapsClient {

    private static final Logger log = LoggerFactory.getLogger(NaverMapsClient.class);
    private final RestClient restClient = RestClient.create();
    private final String clientId;
    private final String secretKey;

    NaverMapsClient(
        @Value("${app.naver.client-id:}") String clientId,
        @Value("${app.naver.secret-key:}") String secretKey
    ) {
        this.clientId = clientId;
        this.secretKey = secretKey;
    }

    boolean isConfigured() {
        return !clientId.isBlank() && !secretKey.isBlank();
    }

    Optional<Coordinate> geocode(String query) {
        if (!isConfigured()) {
            return Optional.empty();
        }
        try {
            URI uri = UriComponentsBuilder
                .fromUriString("https://maps.apigw.ntruss.com/map-geocode/v2/geocode")
                .queryParam("query", query)
                .build()
                .encode()
                .toUri();
            JsonNode body = get(uri);
            JsonNode address = body.path("addresses").path(0);
            if (address.isMissingNode()) {
                return Optional.empty();
            }
            return Optional.of(new Coordinate(address.path("y").asDouble(), address.path("x").asDouble()));
        } catch (RuntimeException error) {
            log.warn("Naver geocoding request failed");
            return Optional.empty();
        }
    }

    Optional<List<List<Double>>> directions(List<Coordinate> points) {
        if (!isConfigured() || points.size() < 2) {
            return Optional.empty();
        }
        try {
            Coordinate start = points.getFirst();
            Coordinate goal = points.getLast();
            String waypoints = points.stream()
                .skip(1)
                .limit(Math.min(5, Math.max(0, points.size() - 2)))
                .map(point -> point.lng() + "," + point.lat())
                .reduce((left, right) -> left + "|" + right)
                .orElse("");
            UriComponentsBuilder builder = UriComponentsBuilder
                .fromUriString("https://maps.apigw.ntruss.com/map-direction/v1/driving")
                .queryParam("start", start.lng() + "," + start.lat())
                .queryParam("goal", goal.lng() + "," + goal.lat())
                .queryParam("option", "traoptimal");
            if (!waypoints.isBlank()) {
                builder.queryParam("waypoints", waypoints);
            }
            JsonNode body = get(builder.build().encode().toUri());
            if (body.path("code").asInt(-1) != 0) {
                return Optional.empty();
            }
            JsonNode path = body.path("route").path("traoptimal").path(0).path("path");
            if (!path.isArray()) {
                return Optional.empty();
            }
            List<List<Double>> result = new ArrayList<>();
            path.forEach(point -> result.add(List.of(point.path(0).asDouble(), point.path(1).asDouble())));
            return Optional.of(result);
        } catch (RuntimeException error) {
            log.warn("Naver directions request failed");
            return Optional.empty();
        }
    }

    private JsonNode get(URI uri) {
        return restClient.get()
            .uri(uri)
            .header("X-NCP-APIGW-API-KEY-ID", clientId)
            .header("X-NCP-APIGW-API-KEY", secretKey)
            .retrieve()
            .body(JsonNode.class);
    }

    record Coordinate(double lat, double lng) {
    }
}
