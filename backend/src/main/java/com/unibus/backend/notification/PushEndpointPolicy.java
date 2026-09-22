package com.unibus.backend.notification;

import java.net.URI;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class PushEndpointPolicy {

    private static final Set<String> STANDARD_HOSTS = Set.of(
        "fcm.googleapis.com", "updates.push.services.mozilla.com", "web.push.apple.com",
        "webpush.push.apple.com"
    );

    private final Set<String> configuredHosts;

    PushEndpointPolicy(@Value("${app.push.allowed-hosts:}") String allowedHosts) {
        this.configuredHosts = java.util.Arrays.stream(allowedHosts.split(","))
            .map(String::trim)
            .map(String::toLowerCase)
            .filter(value -> !value.isEmpty())
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    boolean allows(String value) {
        if (value == null || value.length() > 2_048) return false;
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            int port = uri.getPort();
            if (host == null || !"https".equalsIgnoreCase(uri.getScheme())
                || (port >= 0 && port != 443)) {
                return false;
            }
            host = host.toLowerCase();
            return STANDARD_HOSTS.contains(host) || configuredHosts.contains(host)
                || host.endsWith(".notify.windows.com") || host.endsWith(".push.apple.com");
        } catch (RuntimeException error) {
            return false;
        }
    }

    boolean validSubscriptionKey(String value, int min, int max) {
        return value != null && value.length() >= min && value.length() <= max
            && value.matches("[A-Za-z0-9_-]+");
    }
}
