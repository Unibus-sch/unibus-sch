package com.unibus.backend.auth;

import java.util.UUID;

import com.unibus.backend.common.web.RequestRateLimiter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ExhibitionService {
    static final UUID USER_ID = UUID.fromString("e0000000-0000-4000-8000-000000000026");
    static final String EMAIL = "visitor@unibus.example";

    private final boolean enabled;
    private final AuthRepository repository;
    private final SessionService sessions;
    private final RequestRateLimiter limiter;

    ExhibitionService(@Value("${app.exhibition.enabled:false}") boolean enabled,
                      AuthRepository repository, SessionService sessions, RequestRateLimiter limiter) {
        this.enabled = enabled;
        this.repository = repository;
        this.sessions = sessions;
        this.limiter = limiter;
    }

    @Transactional
    AuthResponses.LoginSuccess login(String identity) {
        if (!enabled) throw new AuthRequestException(HttpStatus.NOT_FOUND, "Not found");
        // Visitors often share the venue Wi-Fi address. Avoid normal account-login limits.
        limiter.enforce("exhibition-login", identity, 600, 900);
        repository.ensureExhibitionUser();
        var user = repository.findById(USER_ID).orElseThrow(() ->
            new AuthRequestException(HttpStatus.SERVICE_UNAVAILABLE, "Exhibition unavailable"));
        if (!"user".equals(user.role()) || !EMAIL.equals(user.email()) || user.passwordHash() != null) {
            throw new AuthRequestException(HttpStatus.SERVICE_UNAVAILABLE, "Exhibition unavailable");
        }
        var session = sessions.issue(user);
        return new AuthResponses.LoginSuccess(true, session.token(), new AuthResponses.User(
            user.id().toString(), user.email(), user.name(), user.studentId(), "user"));
    }
}
