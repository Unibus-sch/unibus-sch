package com.unibus.backend.auth;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class SessionService {

    private final AuthRepository authRepository;
    private final SessionTokenCodec tokenCodec;
    private final Clock clock = Clock.systemUTC();

    SessionService(AuthRepository authRepository, SessionTokenCodec tokenCodec) {
        this.authRepository = authRepository;
        this.tokenCodec = tokenCodec;
    }

    @Transactional
    IssuedSession issue(AuthRepository.UserRecord user) {
        String rawToken = tokenCodec.create();
        String tokenHash = tokenCodec.hash(rawToken);
        OffsetDateTime expiresAt = OffsetDateTime.now(clock).plusDays(30);
        authRepository.insertSession(user.id(), tokenHash, expiresAt);
        return new IssuedSession(rawToken, expiresAt);
    }

    @Transactional
    SessionValidation validate(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return SessionValidation.invalid();
        }

        String tokenHash = tokenCodec.hash(rawToken);
        Optional<AuthRepository.SessionRecord> session = authRepository.findSession(tokenHash);
        if (session.isEmpty()) {
            Optional<AuthRepository.SessionRecord> legacySession = authRepository.findSession(rawToken);
            if (legacySession.isPresent()) {
                authRepository.replaceSessionToken(rawToken, tokenHash);
                session = legacySession;
            }
        }
        if (session.isEmpty()) {
            return SessionValidation.invalid();
        }

        AuthRepository.SessionRecord current = session.orElseThrow();
        if (current.expiresAt().isBefore(OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC))) {
            return SessionValidation.expired();
        }

        return authRepository.findById(current.userId())
            .map(SessionValidation::valid)
            .orElseGet(SessionValidation::invalid);
    }

    @Transactional
    void delete(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        authRepository.deleteSessionTokens(tokenCodec.hash(rawToken), rawToken);
    }

    record IssuedSession(String token, OffsetDateTime expiresAt) {
    }

    record SessionValidation(Status status, AuthRepository.UserRecord user) {

        enum Status {
            VALID,
            INVALID,
            EXPIRED
        }

        static SessionValidation valid(AuthRepository.UserRecord user) {
            return new SessionValidation(Status.VALID, user);
        }

        static SessionValidation invalid() {
            return new SessionValidation(Status.INVALID, null);
        }

        static SessionValidation expired() {
            return new SessionValidation(Status.EXPIRED, null);
        }
    }
}
