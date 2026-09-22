package com.unibus.backend.auth;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import com.unibus.backend.common.web.RequestRateLimiter;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

@Service
class AuthService {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final int MAX_PASSWORD_LENGTH = 72;
    private static final String DUMMY_PASSWORD_HASH =
        "$2b$10$lQNuP4A50wwzI6F9pHVk.eKOdopKISxcT598MTscQR8hgciVaZ8/u";

    private final AuthRepository authRepository;
    private final SessionService sessionService;
    private final PasswordEncoder passwordEncoder;
    private final RequestRateLimiter requestRateLimiter;
    private final KakaoClient kakaoClient;

    AuthService(
        AuthRepository authRepository,
        SessionService sessionService,
        PasswordEncoder passwordEncoder,
        RequestRateLimiter requestRateLimiter,
        KakaoClient kakaoClient
    ) {
        this.authRepository = authRepository;
        this.sessionService = sessionService;
        this.passwordEncoder = passwordEncoder;
        this.requestRateLimiter = requestRateLimiter;
        this.kakaoClient = kakaoClient;
    }

    AuthResponses.SignupData signup(JsonNode body, String requestIdentity) {
        String email = requiredString(body, "email", "Missing required fields");
        String password = requiredString(body, "password", "Missing required fields");
        String name = requiredString(body, "name", "Missing required fields");

        requestRateLimiter.enforce("auth-signup", requestIdentity, 30, 3_600);

        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        String trimmedName = name.trim();
        if (normalizedEmail.length() > 254 || !EMAIL_PATTERN.matcher(normalizedEmail).matches()) {
            throw badRequest("Invalid email format");
        }
        if (trimmedName.isEmpty() || trimmedName.length() > 100) {
            throw badRequest("Name must be between 1 and 100 characters");
        }
        if (password.length() < MIN_PASSWORD_LENGTH || password.length() > MAX_PASSWORD_LENGTH) {
            throw badRequest("Password must be between 8 and 72 characters");
        }

        String studentId = null;
        if (body.has("studentId")) {
            JsonNode studentIdNode = body.get("studentId");
            if (studentIdNode == null || !studentIdNode.isString()
                || studentIdNode.stringValue().trim().length() > 50) {
                throw badRequest("Invalid student ID");
            }
            studentId = emptyToNull(studentIdNode.stringValue().trim());
        }
        if (authRepository.findByEmail(normalizedEmail).isPresent()) {
            throw badRequest("User already exists");
        }

        AuthRepository.UserRecord user = authRepository.insertLocalUser(
            normalizedEmail,
            passwordEncoder.encode(password),
            trimmedName,
            studentId
        );
        return new AuthResponses.SignupData(toUser(user));
    }

    AuthResponses.LoginSuccess login(JsonNode body, String requestIdentity) {
        String email = requiredString(body, "email", "Missing email or password");
        String password = requiredString(body, "password", "Missing email or password");
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);

        if (normalizedEmail.length() > 254 || !EMAIL_PATTERN.matcher(normalizedEmail).matches()
            || password.isEmpty() || password.length() > MAX_PASSWORD_LENGTH) {
            throw unauthorized("Invalid credentials");
        }

        requestRateLimiter.enforce("auth-login-ip", requestIdentity, 100, 900);
        requestRateLimiter.enforce("auth-login-account", normalizedEmail, 10, 900);

        Optional<AuthRepository.UserRecord> found = authRepository.findByEmail(normalizedEmail);
        if (found.isEmpty()) {
            passwordEncoder.matches(password, DUMMY_PASSWORD_HASH);
            throw unauthorized("Invalid credentials");
        }

        AuthRepository.UserRecord user = found.orElseThrow();
        if (!"local".equals(user.provider())) {
            passwordEncoder.matches(password, DUMMY_PASSWORD_HASH);
            throw unauthorized("Invalid credentials");
        }

        if (!matches(password, user.passwordHash())) {
            throw unauthorized("Invalid credentials");
        }

        requestRateLimiter.clear("auth-login-account", normalizedEmail);
        SessionService.IssuedSession session = sessionService.issue(user);
        return new AuthResponses.LoginSuccess(true, session.token(), toUser(user));
    }

    AuthResponses.KakaoLoginSuccess kakao(JsonNode body, String requestIdentity) {
        String accessToken = requiredString(body, "accessToken", "Missing Kakao access token");
        if (accessToken.length() < 20 || accessToken.length() > 4_096) {
            throw badRequest("Missing Kakao access token");
        }
        requestRateLimiter.enforce("auth-kakao", requestIdentity, 100, 900);

        KakaoClient.KakaoLookup lookup = kakaoClient.fetchProfile(accessToken);
        if (!lookup.tokenValid()) {
            throw unauthorized("Invalid Kakao token");
        }
        KakaoClient.KakaoProfile profile = lookup.profile();
        if (profile == null) {
            throw unauthorized("Invalid Kakao profile");
        }
        AuthRepository.UserRecord user = authRepository.findKakaoUser(profile.id())
            .orElseGet(() -> authRepository.insertKakaoUser(
                profile.email() == null || profile.email().isBlank()
                    ? "kakao_" + profile.id() + "@kakao.local"
                    : profile.email(),
                profile.name(),
                profile.id(),
                profile.profileImage()
            ));
        SessionService.IssuedSession session = sessionService.issue(user);
        return new AuthResponses.KakaoLoginSuccess(true, session.token(), toKakaoUser(user));
    }

    void logout(String rawToken) {
        sessionService.delete(rawToken);
    }

    private boolean matches(String password, String passwordHash) {
        if (passwordHash == null || passwordHash.isBlank()) {
            return false;
        }
        try {
            return passwordEncoder.matches(password, passwordHash);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private String requiredString(JsonNode body, String field, String errorMessage) {
        if (body == null || !body.isObject() || !body.has(field) || !body.get(field).isString()) {
            throw badRequest(errorMessage);
        }
        return body.get(field).stringValue();
    }

    private AuthResponses.User toUser(AuthRepository.UserRecord user) {
        return new AuthResponses.User(
            user.id().toString(), user.email(), user.name(), user.studentId(), user.role()
        );
    }

    private AuthResponses.KakaoUser toKakaoUser(AuthRepository.UserRecord user) {
        return new AuthResponses.KakaoUser(
            user.id().toString(), user.email(), user.name(), user.profileImage(),
            user.studentId(), user.role(), user.provider()
        );
    }

    private String emptyToNull(String value) {
        return value.isEmpty() ? null : value;
    }

    private AuthRequestException badRequest(String message) {
        return new AuthRequestException(HttpStatus.BAD_REQUEST, message);
    }

    private AuthRequestException unauthorized(String message) {
        return new AuthRequestException(HttpStatus.UNAUTHORIZED, message);
    }
}
