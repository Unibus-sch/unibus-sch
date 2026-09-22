package com.unibus.backend.auth;

import jakarta.servlet.http.HttpServletRequest;

public final class AdminRequest {

    private AdminRequest() {
    }

    public static SessionAuthenticator.User user(HttpServletRequest request) {
        return (SessionAuthenticator.User) request.getAttribute(
            AdminAuthenticationFilter.ADMIN_USER_ATTRIBUTE
        );
    }
}
