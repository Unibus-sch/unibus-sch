package com.unibus.backend.auth;

import org.springframework.stereotype.Component;

@Component
public class SessionAuthenticator {

    private final SessionService sessionService;

    SessionAuthenticator(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    public Validation validate(String rawToken) {
        SessionService.SessionValidation validation = sessionService.validate(rawToken);
        if (validation.status() == SessionService.SessionValidation.Status.INVALID) {
            return Validation.invalid();
        }
        if (validation.status() == SessionService.SessionValidation.Status.EXPIRED) {
            return Validation.expired();
        }
        AuthRepository.UserRecord user = validation.user();
        return Validation.valid(new User(
            user.id().toString(), user.email(), user.name(), user.role()
        ));
    }

    public record User(String id, String email, String name, String role) {
    }

    public record Validation(Status status, User user) {

        public enum Status {
            VALID,
            INVALID,
            EXPIRED
        }

        static Validation valid(User user) {
            return new Validation(Status.VALID, user);
        }

        static Validation invalid() {
            return new Validation(Status.INVALID, null);
        }

        static Validation expired() {
            return new Validation(Status.EXPIRED, null);
        }
    }
}
