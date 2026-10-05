package com.unibus.backend.report;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import com.unibus.backend.common.api.ApiRequestException;
import com.unibus.backend.common.web.RequestRateLimiter;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ReportCreateServiceTest {
    @Test
    void emptyArraysKeepEdgeValidationRatherThanBecomingJsonTextOrDefaultCategory() {
        var repository = mock(ReportAdminRepository.class);
        var service = new ReportCreateService(repository, mock(RequestRateLimiter.class));
        var mapper = new ObjectMapper();
        for (String body : new String[]{"{\"title\":[],\"details\":\"D\"}",
            "{\"title\":\"T\",\"details\":\"D\",\"category\":[]}",
            "{\"title\":\"T\",\"details\":\"D\",\"relatedBusId\":[]}"}) {
            assertThatThrownBy(() -> service.create(mapper.readTree(body), "50000000-0000-0000-0000-000000000002"))
                .isInstanceOf(ApiRequestException.class);
        }
        verifyNoInteractions(repository);
    }
}
