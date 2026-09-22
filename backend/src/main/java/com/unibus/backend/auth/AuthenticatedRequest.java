package com.unibus.backend.auth;

import jakarta.servlet.http.HttpServletRequest;

public final class AuthenticatedRequest {

    private AuthenticatedRequest() {
    }

    public static SessionAuthenticator.User user(HttpServletRequest request) {
        return (SessionAuthenticator.User) request.getAttribute(
            UserAuthenticationFilter.AUTHENTICATED_USER_ATTRIBUTE
        );
    }
}
