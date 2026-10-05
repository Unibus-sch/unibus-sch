package com.unibus.backend.route;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.unibus.backend.common.web.RequestRateLimiter;
import com.unibus.backend.common.web.RateLimitExceededException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import tools.jackson.databind.ObjectMapper;

class CampusPathServiceTest {
    private final RouteRepository repository = mock(RouteRepository.class);
    private final NaverMapsClient naver = mock(NaverMapsClient.class);
    private final RequestRateLimiter limiter = mock(RequestRateLimiter.class);
    private final CampusPathService service = new CampusPathService(repository, naver, limiter, new ObjectMapper());

    @Test
    void defaultFallbackPreservesVisibleStopsLoopInterpolationAndMemoryCache() {
        var first = service.path("client");
        assertThat(first.get("cached")).isEqualTo(false);
        var json = new ObjectMapper().valueToTree(first);
        assertThat(json.path("stops").size()).isEqualTo(5);
        assertThat(json.path("stops").get(0).has("order")).isFalse();
        assertThat(json.path("path").size()).isEqualTo(100);
        assertThat(json.path("path").get(99).get(0).asDouble()).isEqualTo(126.933816);
        assertThat(json.path("path").get(99).get(1).asDouble()).isEqualTo(36.772760);
        assertThat(service.path("client").get("cached")).isEqualTo(true);
        verify(limiter, times(1)).enforce("campus-path", "client", 30, 600);
        verify(repository, never()).saveCachedPath(any(), any(), any());
    }

    @Test
    void storedRouteIncludesShapePointsAndInvalidatesChangedInputs() {
        UUID id = UUID.randomUUID();
        when(repository.findCampusRouteId()).thenReturn(Optional.of(id));
        when(repository.findStops(id)).thenReturn(List.of(
            new RouteResponse.Stop("a", "A", 1, 36.0, 126.0, null),
            new RouteResponse.Stop("b", "B", 2, 37.0, 127.0, null)));
        when(repository.findShapePoints(id)).thenReturn(List.of(
            new RouteResponse.ShapePoint("shape", null, 1, 1, 36.5, 126.5)));
        var expected = List.of(List.of(126.0, 36.0), List.of(127.0, 37.0));
        when(naver.directions(List.of(new NaverMapsClient.Coordinate(36, 126),
            new NaverMapsClient.Coordinate(36.5, 126.5), new NaverMapsClient.Coordinate(37, 127))))
            .thenReturn(Optional.of(expected));
        assertThat(service.path("client").get("path")).isEqualTo(expected);
        verify(repository).saveCachedPath(eq(id), any(), eq(expected));
        when(repository.findStops(id)).thenReturn(List.of(
            new RouteResponse.Stop("a", "A", 1, 38.0, 128.0, null),
            new RouteResponse.Stop("b", "B", 2, 37.0, 127.0, null)));
        assertThat(service.path("client").get("cached")).isEqualTo(false);
        verify(limiter, times(2)).enforce("campus-path", "client", 30, 600);
    }

    @Test
    void optionalDatabaseCacheFailureDoesNotFailPathResponse() {
        UUID id = UUID.randomUUID();
        when(repository.findCampusRouteId()).thenReturn(Optional.of(id));
        when(repository.findStops(id)).thenReturn(List.of(
            new RouteResponse.Stop("a", "A", 1, 36.0, 126.0, null),
            new RouteResponse.Stop("b", "B", 2, 37.0, 127.0, null)));
        doThrow(new DataAccessResourceFailureException("test failure")).when(repository).saveCachedPath(any(), any(), any());
        assertThat(service.path("client").get("cached")).isEqualTo(false);
    }

    @Test
    void cacheMissRateLimitKeepsJsonAndRetryHeader() {
        doThrow(new RateLimitExceededException(600)).when(limiter).enforce("campus-path", "unknown-client", 30, 600);
        var response = new CampusController(service).path(new org.springframework.mock.web.MockHttpServletRequest());
        assertThat(response.getStatusCode().value()).isEqualTo(429);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("600");
        assertThat(response.getHeaders().getFirst("Cache-Control")).isEqualTo("public, max-age=300, stale-while-revalidate=3600");
    }
}
