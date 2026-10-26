package com.unibus.backend.auth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import com.unibus.backend.common.web.RequestRateLimiter;
import jakarta.servlet.FilterChain;
import tools.jackson.databind.ObjectMapper;

class ExhibitionServiceTest {
    private AuthRepository.UserRecord visitor(String role) {
        return new AuthRepository.UserRecord(ExhibitionService.USER_ID, ExhibitionService.EMAIL,
            null, "방문객", "20260000", role, "local", null, null);
    }

    @Test
    void ordinarySignupCannotClaimTheReservedEmail() {
        var repository = mock(AuthRepository.class);
        var sessions = mock(SessionService.class);
        var service = new AuthService(repository, sessions,
            mock(org.springframework.security.crypto.password.PasswordEncoder.class),
            mock(RequestRateLimiter.class), mock(KakaoClient.class));
        var body = new ObjectMapper().readTree("""
            {"email":"visitor@unibus.example","name":"Other","password":"Password123!"}
            """);
        assertThatThrownBy(() -> service.signup(body, "client")).isInstanceOf(AuthRequestException.class);
        verifyNoInteractions(repository, sessions);
    }

    @Test
    void disabledEntryDoesNotCreateAnAccountOrSession() {
        var repository = mock(AuthRepository.class);
        var sessions = mock(SessionService.class);
        var limiter = mock(RequestRateLimiter.class);
        var service = new ExhibitionService(false, repository, sessions, limiter);
        assertThatThrownBy(() -> service.login("venue"))
            .isInstanceOf(AuthRequestException.class);
        verifyNoInteractions(repository, sessions, limiter);
    }

    @Test
    void eachLoginIssuesAnIndependentUserSessionWithoutAcceptingCredentials() {
        var repository = mock(AuthRepository.class);
        var sessions = mock(SessionService.class);
        var user = visitor("user");
        when(repository.findById(ExhibitionService.USER_ID)).thenReturn(Optional.of(user));
        when(sessions.issue(user)).thenReturn(
            new SessionService.IssuedSession("first-token", OffsetDateTime.now().plusHours(4)),
            new SessionService.IssuedSession("second-token", OffsetDateTime.now().plusHours(4)));
        var service = new ExhibitionService(true, repository, sessions, mock(RequestRateLimiter.class));
        var first = service.login("venue");
        var second = service.login("venue");
        assertThat(first.token()).isNotEqualTo(second.token());
        assertThat(first.user().role()).isEqualTo("user");
        verify(repository, times(2)).ensureExhibitionUser();
    }

    @Test
    void privilegedOrMissingReservedAccountNeverReceivesASession() {
        var repository = mock(AuthRepository.class);
        var sessions = mock(SessionService.class);
        var service = new ExhibitionService(true, repository, sessions, mock(RequestRateLimiter.class));
        for (var user : new AuthRepository.UserRecord[]{visitor("admin"), visitor("driver")}) {
            when(repository.findById(ExhibitionService.USER_ID)).thenReturn(Optional.of(user));
            assertThatThrownBy(() -> service.login("venue")).isInstanceOf(AuthRequestException.class);
        }
        when(repository.findById(ExhibitionService.USER_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.login("venue")).isInstanceOf(AuthRequestException.class);
        verifyNoInteractions(sessions);
    }

    @Test
    void visitorSessionsExpireInFourHoursAndCannotSurviveFeatureDisableOrRoleChange() {
        var repository = mock(AuthRepository.class);
        var codec = new SessionTokenCodec();
        var service = new SessionService(repository, codec);
        var issued = service.issue(visitor("user"));
        assertThat(issued.expiresAt()).isBetween(OffsetDateTime.now().plusHours(3), OffsetDateTime.now().plusHours(4));
        when(repository.findSession(codec.hash(issued.token()))).thenReturn(Optional.of(
            new AuthRepository.SessionRecord(ExhibitionService.USER_ID, codec.hash(issued.token()), issued.expiresAt())));
        when(repository.findById(ExhibitionService.USER_ID)).thenReturn(Optional.of(visitor("user")));
        assertThat(service.validate(issued.token()).status()).isEqualTo(SessionService.SessionValidation.Status.INVALID);
        ReflectionTestUtils.setField(service, "exhibitionEnabled", true);
        assertThat(service.validate(issued.token()).status()).isEqualTo(SessionService.SessionValidation.Status.VALID);
        when(repository.findById(ExhibitionService.USER_ID)).thenReturn(Optional.of(visitor("admin")));
        assertThat(service.validate(issued.token()).status()).isEqualTo(SessionService.SessionValidation.Status.INVALID);
    }

    @Test
    void visitorCannotWriteReportsOrPushSubscriptions() throws Exception {
        var authenticator = mock(SessionAuthenticator.class);
        when(authenticator.validate("visitor-token")).thenReturn(SessionAuthenticator.Validation.valid(
            new SessionAuthenticator.User(ExhibitionService.USER_ID.toString(), ExhibitionService.EMAIL, "방문객", "user")));
        var filter = new UserAuthenticationFilter(authenticator, new ObjectMapper());
        for (String path : new String[]{"/reports", "/notifications/subscribe", "/notifications/unsubscribe"}) {
            var request = new MockHttpServletRequest("POST", path);
            request.addHeader("X-Auth-Token", "visitor-token");
            var response = new MockHttpServletResponse();
            var chain = mock(FilterChain.class);
            filter.doFilter(request, response, chain);
            assertThat(response.getStatus()).isEqualTo(403);
            verifyNoInteractions(chain);
        }
    }
}
