package com.unibus.backend.auth;

import jakarta.servlet.http.HttpServletRequest;

public final class DriverRequest {

    private DriverRequest() {
    }

    public static SessionAuthenticator.User user(HttpServletRequest request) {
        return (SessionAuthenticator.User) request.getAttribute(
            DriverAuthenticationFilter.DRIVER_USER_ATTRIBUTE
        );
    }
}
