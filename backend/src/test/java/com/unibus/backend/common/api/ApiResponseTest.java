package com.unibus.backend.common.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

class ApiResponseTest {

    @Test
    void buildsExistingSuccessEnvelopeShape() {
        ApiResponse<Map<String, String>> response = ApiResponse.success(Map.of("status", "UP"));

        assertThat(response.success()).isTrue();
        assertThat(response.data()).containsEntry("status", "UP");
        assertThat(response.error()).isNull();
    }

    @Test
    void buildsExistingErrorEnvelopeShape() {
        ApiResponse<Void> response = ApiResponse.error("Not Found");

        assertThat(response.success()).isFalse();
        assertThat(response.data()).isNull();
        assertThat(response.error()).isEqualTo("Not Found");
    }
}
