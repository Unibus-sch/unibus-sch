package com.unibus.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class BcryptCompatibilityTest {

    private static final String BCRYPT_JS_HASH =
        "$2b$10$R09SClXZReLIXPLYF/t0nezxDXEBBiUQ8YFHoP3rZ50ynwzk52U3W";

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(
        BCryptPasswordEncoder.BCryptVersion.$2B,
        10
    );

    @Test
    void verifiesExistingBcryptJs2bHash() {
        assertThat(encoder.matches("P@ssw0rd!기존", BCRYPT_JS_HASH)).isTrue();
        assertThat(encoder.matches("wrong-password", BCRYPT_JS_HASH)).isFalse();
    }

    @Test
    void verifiesExistingBcryptJsHashWhenUtf8InputExceedsSeventyTwoBytes() {
        String bcryptJsHash = "$2b$10$1zotLwSc4jyNw4Yrd5PcJeOlCJrG0aY3lA1aHDEFUgqY2Jrpw9zW.";

        assertThat(encoder.matches("가".repeat(40), bcryptJsHash)).isTrue();
    }

    @Test
    void createsHashesWithTheExistingVersionAndCost() {
        assertThat(encoder.encode("new-password")).startsWith("$2b$10$");
    }
}
