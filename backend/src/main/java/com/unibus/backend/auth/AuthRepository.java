package com.unibus.backend.auth;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class AuthRepository {

    private static final String USER_COLUMNS = """
        id, email, password_hash, name, student_id, role, provider, provider_id, profile_image
        """;

    private final JdbcTemplate jdbcTemplate;

    AuthRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    Optional<UserRecord> findByEmail(String email) {
        return jdbcTemplate.query(
            "SELECT " + USER_COLUMNS + " FROM users WHERE LOWER(email) = ?",
            (resultSet, rowNumber) -> mapUser(resultSet),
            email
        ).stream().findFirst();
    }

    Optional<UserRecord> findById(UUID id) {
        return jdbcTemplate.query(
            "SELECT " + USER_COLUMNS + " FROM users WHERE id = ?",
            (resultSet, rowNumber) -> mapUser(resultSet),
            id
        ).stream().findFirst();
    }

    UserRecord insertLocalUser(
        String email,
        String passwordHash,
        String name,
        String studentId
    ) {
        return jdbcTemplate.queryForObject("""
            INSERT INTO users (email, password_hash, name, student_id, role, provider)
            VALUES (?, ?, ?, ?, 'user', 'local')
            RETURNING id, email, password_hash, name, student_id, role, provider,
                      provider_id, profile_image
            """, (resultSet, rowNumber) -> mapUser(resultSet), email, passwordHash, name, studentId);
    }

    Optional<UserRecord> findKakaoUser(String providerId) {
        return jdbcTemplate.query(
            "SELECT " + USER_COLUMNS + " FROM users WHERE provider = 'kakao' AND provider_id = ?",
            (resultSet, rowNumber) -> mapUser(resultSet),
            providerId
        ).stream().findFirst();
    }

    UserRecord insertKakaoUser(
        String email,
        String name,
        String providerId,
        String profileImage
    ) {
        return jdbcTemplate.queryForObject("""
            INSERT INTO users (email, name, provider, provider_id, profile_image, role)
            VALUES (?, ?, 'kakao', ?, ?, 'user')
            RETURNING id, email, password_hash, name, student_id, role, provider,
                      provider_id, profile_image
            """, (resultSet, rowNumber) -> mapUser(resultSet), email, name, providerId, profileImage);
    }

    void insertSession(UUID userId, String tokenHash, OffsetDateTime expiresAt) {
        jdbcTemplate.update(
            "INSERT INTO auth_tokens (user_id, token, expires_at) VALUES (?, ?, ?)",
            userId, tokenHash, expiresAt
        );
    }

    Optional<SessionRecord> findSession(String storedToken) {
        return jdbcTemplate.query("""
            SELECT user_id, token, expires_at
            FROM auth_tokens
            WHERE token = ?
            """, (resultSet, rowNumber) -> new SessionRecord(
                resultSet.getObject("user_id", UUID.class),
                resultSet.getString("token"),
                resultSet.getObject("expires_at", OffsetDateTime.class)
            ), storedToken).stream().findFirst();
    }

    void replaceSessionToken(String legacyToken, String tokenHash) {
        jdbcTemplate.update(
            "UPDATE auth_tokens SET token = ? WHERE token = ?",
            tokenHash, legacyToken
        );
    }

    void deleteSessionTokens(String tokenHash, String rawToken) {
        jdbcTemplate.update(
            "DELETE FROM auth_tokens WHERE token = ? OR token = ?",
            tokenHash, rawToken
        );
    }

    private UserRecord mapUser(ResultSet resultSet) throws SQLException {
        return new UserRecord(
            resultSet.getObject("id", UUID.class),
            resultSet.getString("email"),
            resultSet.getString("password_hash"),
            resultSet.getString("name"),
            resultSet.getString("student_id"),
            resultSet.getString("role"),
            resultSet.getString("provider"),
            resultSet.getString("provider_id"),
            resultSet.getString("profile_image")
        );
    }

    record UserRecord(
        UUID id,
        String email,
        String passwordHash,
        String name,
        String studentId,
        String role,
        String provider,
        String providerId,
        String profileImage
    ) {
    }

    record SessionRecord(UUID userId, String token, OffsetDateTime expiresAt) {
    }
}
