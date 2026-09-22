package com.unibus.backend.auth;

final class AuthResponses {

    private AuthResponses() {
    }

    record User(
        String id,
        String email,
        String name,
        String studentId,
        String role
    ) {
    }

    record KakaoUser(
        String id,
        String email,
        String name,
        String profileImage,
        String studentId,
        String role,
        String provider
    ) {
    }

    record SignupData(User user) {
    }

    record LoginSuccess(boolean success, String token, User user) {
    }

    record KakaoLoginSuccess(boolean success, String token, KakaoUser user) {
    }

    record LogoutSuccess(boolean success, String message) {
    }
}
