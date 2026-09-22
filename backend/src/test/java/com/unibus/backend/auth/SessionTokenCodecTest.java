package com.unibus.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SessionTokenCodecTest {

    private final SessionTokenCodec codec = new SessionTokenCodec();

    @Test
    void hashesTokensExactlyLikeTheEdgeWebCryptoImplementation() {
        assertThat(codec.hash("edge-issued-session-token_1234567890"))
            .isEqualTo("sha256:98LYBMfn0abs1M6zx6IJwUpA7m3fRKMlF3JJNKPenhU");
    }

    @Test
    void createsThirtyTwoByteBase64UrlTokens() {
        String first = codec.create();
        String second = codec.create();

        assertThat(first).matches("[A-Za-z0-9_-]{43}");
        assertThat(second).matches("[A-Za-z0-9_-]{43}").isNotEqualTo(first);
    }
}
