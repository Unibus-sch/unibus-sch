package com.unibus.backend.common.api;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class EdgeJsonTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void preservesJavascriptNullEmptyArrayAndNumericCoercions() {
        assertThat(EdgeJson.truthy(mapper.readTree("[]"))).isTrue();
        assertThat(EdgeJson.truthy(mapper.readTree("0"))).isFalse();
        assertThat(EdgeJson.string(mapper.readTree("[]"))).isEmpty();
        assertThat(EdgeJson.string(mapper.readTree("[null,1,[2,3],{}]"))).isEqualTo(",1,2,3,[object Object]");
        assertThat(EdgeJson.number(mapper.readTree("null"))).isZero();
        assertThat(EdgeJson.number(mapper.readTree("[]"))).isZero();
        assertThat(EdgeJson.number(mapper.readTree("[36.77]"))).isEqualTo(36.77);
        assertThat(EdgeJson.number(mapper.readTree("\"0x5a\""))).isEqualTo(90);
        assertThat(EdgeJson.number(mapper.readTree("[1,2]"))).isNaN();
        assertThat(EdgeJson.number(mapper.readTree("{}"))).isNaN();
        assertThat(EdgeJson.number(mapper.readTree("\"1d\""))).isNaN();
        assertThat(EdgeJson.number(mapper.readTree("\"0xffffffffffffffff\""))).isGreaterThan(250);
        assertThat(EdgeJson.string(mapper.readTree("1e21"))).isEqualTo("1e+21");
    }
}
