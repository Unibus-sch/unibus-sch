package com.unibus.backend.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ContentLengthLimitFilterTest {

    private final ContentLengthLimitFilter filter = new ContentLengthLimitFilter();

    @Test
    void rejectsRequestsLargerThanTheEdgeFunctionLimit() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/notices/images");
        request.setContent(new byte[(int) ContentLengthLimitFilter.MAX_REQUEST_BYTES + 1]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean continued = new AtomicBoolean(false);

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> continued.set(true));

        assertThat(continued).isFalse();
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString())
            .isEqualTo("{\"success\":false,\"error\":\"Request body is too large\"}");
    }

    @Test
    void allowsRequestsAtTheLimit() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/notices/images");
        request.setContent(new byte[(int) ContentLengthLimitFilter.MAX_REQUEST_BYTES]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean continued = new AtomicBoolean(false);

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> continued.set(true));

        assertThat(continued).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }
}
