package com.unibus.backend.notice;

import java.net.URI;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

@Component
class NoticeStorageClient {

    private static final String BUCKET = "notice-images";
    private final RestClient restClient = RestClient.create();
    private final String apiUrl;
    private final String serviceRoleKey;

    NoticeStorageClient(
        @Value("${app.supabase.api-url:}") String apiUrl,
        @Value("${app.supabase.service-role-key:}") String serviceRoleKey
    ) {
        this.apiUrl = stripTrailingSlash(apiUrl);
        this.serviceRoleKey = serviceRoleKey;
    }

    String upload(byte[] bytes, String contentType, String extension) {
        if (apiUrl.isBlank() || serviceRoleKey.isBlank()) {
            throw new IllegalStateException("Supabase Storage is not configured");
        }
        String filename = System.currentTimeMillis() + "_" + UUID.randomUUID() + "." + extension;
        URI uploadUri = UriComponentsBuilder.fromUriString(apiUrl)
            .pathSegment("storage", "v1", "object", BUCKET, filename)
            .build().encode().toUri();
        restClient.post()
            .uri(uploadUri)
            .header("Authorization", "Bearer " + serviceRoleKey)
            .header("apikey", serviceRoleKey)
            .header("x-upsert", "false")
            .contentType(MediaType.parseMediaType(contentType))
            .body(bytes)
            .retrieve()
            .toBodilessEntity();
        return apiUrl + "/storage/v1/object/public/" + BUCKET + "/" + filename;
    }

    Optional<String> storageOrigin() {
        if (apiUrl.isBlank()) {
            return Optional.empty();
        }
        URI uri = URI.create(apiUrl);
        int port = uri.getPort();
        return Optional.of(uri.getScheme() + "://" + uri.getHost() + (port < 0 ? "" : ":" + port));
    }

    private String stripTrailingSlash(String value) {
        return value == null ? "" : value.replaceAll("/+$", "");
    }
}
