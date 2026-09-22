package com.unibus.backend.notification;

import java.util.UUID;

import com.unibus.backend.common.api.ApiRequestException;
import com.unibus.backend.common.web.RequestRateLimiter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Service
class PushSubscriptionService {

    private final PushSubscriptionRepository repository;
    private final PushEndpointPolicy endpointPolicy;
    private final RequestRateLimiter rateLimiter;
    private final String publicKey;

    PushSubscriptionService(
        PushSubscriptionRepository repository,
        PushEndpointPolicy endpointPolicy,
        RequestRateLimiter rateLimiter,
        @Value("${app.push.vapid-public-key:}") String publicKey
    ) {
        this.repository = repository;
        this.endpointPolicy = endpointPolicy;
        this.rateLimiter = rateLimiter;
        this.publicKey = publicKey;
    }

    PublicKey publicKey() {
        return new PublicKey(publicKey);
    }

    @Transactional
    SubscriptionResult subscribe(JsonNode body, String rawUserId, String rawUserAgent) {
        UUID userId = UUID.fromString(rawUserId);
        rateLimiter.enforce("push-subscribe", rawUserId, 20, 3_600);

        JsonNode subscription = body == null ? null : body.get("subscription");
        String endpoint = text(subscription, "endpoint");
        JsonNode keys = subscription == null ? null : subscription.get("keys");
        String p256dh = text(keys, "p256dh");
        String auth = text(keys, "auth");
        if (!endpointPolicy.allows(endpoint)
            || !endpointPolicy.validSubscriptionKey(p256dh, 40, 200)
            || !endpointPolicy.validSubscriptionKey(auth, 10, 100)) {
            throw new ApiRequestException(HttpStatus.BAD_REQUEST, "Invalid push subscription");
        }

        String userAgent = rawUserAgent == null || rawUserAgent.isBlank()
            ? null : rawUserAgent.substring(0, Math.min(500, rawUserAgent.length()));
        PushSubscriptionRepository.ExistingSubscription existing = repository.findByEndpoint(endpoint)
            .orElse(null);
        if (existing != null && !existing.userId().equals(userId)) {
            throw new ApiRequestException(
                HttpStatus.CONFLICT, "This push subscription belongs to another account"
            );
        }
        UUID id = existing == null
            ? repository.insert(userId, endpoint, p256dh, auth, userAgent)
            : repository.update(existing.id(), userId, endpoint, p256dh, auth, userAgent);
        return new SubscriptionResult(id.toString());
    }

    @Transactional
    void unsubscribe(JsonNode body, String rawUserId) {
        String endpoint = text(body, "endpoint");
        if (!endpointPolicy.allows(endpoint)) {
            throw new ApiRequestException(HttpStatus.BAD_REQUEST, "endpoint is required");
        }
        repository.disable(UUID.fromString(rawUserId), endpoint);
    }

    private String text(JsonNode node, String field) {
        return node != null && node.has(field) && node.get(field).isString()
            ? node.get(field).stringValue() : "";
    }

    record PublicKey(String publicKey) {
    }

    record SubscriptionResult(String id) {
    }
}
