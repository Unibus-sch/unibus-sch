package com.unibus.backend.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

@Component
class KakaoClient {

    private final RestClient restClient;
    private final String userInfoUrl;

    KakaoClient(
        @Value("${app.kakao.user-info-url}") String userInfoUrl
    ) {
        this.restClient = RestClient.create();
        this.userInfoUrl = userInfoUrl;
    }

    KakaoLookup fetchProfile(String accessToken) {
        try {
            JsonNode body = restClient.get()
                .uri(userInfoUrl)
                .header("Authorization", "Bearer " + accessToken)
                .retrieve()
                .body(JsonNode.class);
            if (body == null) {
                return KakaoLookup.invalidProfile();
            }

            String id = body.path("id").asString("");
            if (id.isBlank()) {
                return KakaoLookup.invalidProfile();
            }
            JsonNode account = body.path("kakao_account");
            JsonNode profile = account.path("profile");
            String email = account.path("email").stringValue();
            String name = profile.path("nickname").asString("Kakao User");
            String profileImage = profile.path("profile_image_url").stringValue();
            return KakaoLookup.valid(new KakaoProfile(id, email, name, profileImage));
        } catch (RestClientResponseException error) {
            return KakaoLookup.invalidToken();
        }
    }

    record KakaoLookup(boolean tokenValid, KakaoProfile profile) {

        static KakaoLookup valid(KakaoProfile profile) {
            return new KakaoLookup(true, profile);
        }

        static KakaoLookup invalidProfile() {
            return new KakaoLookup(true, null);
        }

        static KakaoLookup invalidToken() {
            return new KakaoLookup(false, null);
        }
    }

    record KakaoProfile(String id, String email, String name, String profileImage) {
    }
}
