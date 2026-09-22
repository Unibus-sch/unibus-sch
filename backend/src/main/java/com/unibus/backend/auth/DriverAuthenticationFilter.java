package com.unibus.backend.auth;

import java.io.IOException;
import java.util.Map;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

@Component
public class DriverAuthenticationFilter extends OncePerRequestFilter {

    public static final String DRIVER_USER_ATTRIBUTE = "unibus.driver.user";

    private final SessionAuthenticator sessionAuthenticator;
    private final ObjectMapper objectMapper;

    public DriverAuthenticationFilter(
        SessionAuthenticator sessionAuthenticator,
        ObjectMapper objectMapper
    ) {
        this.sessionAuthenticator = sessionAuthenticator;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !("/driver".equals(path) || path.startsWith("/driver/"));
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        String rawToken = request.getHeader("X-Auth-Token");
        if (rawToken == null || rawToken.isBlank()) {
            writeError(response, 401, "Unauthorized: No token provided");
            return;
        }

        SessionAuthenticator.Validation validation = sessionAuthenticator.validate(rawToken);
        if (validation.status() == SessionAuthenticator.Validation.Status.INVALID) {
            writeError(response, 401, "Unauthorized: Invalid token");
            return;
        }
        if (validation.status() == SessionAuthenticator.Validation.Status.EXPIRED) {
            writeError(response, 401, "Unauthorized: Token expired");
            return;
        }
        if (!"driver".equals(validation.user().role()) && !"admin".equals(validation.user().role())) {
            writeError(response, 403, "Forbidden: Driver or Admin access required");
            return;
        }

        request.setAttribute(DRIVER_USER_ATTRIBUTE, validation.user());
        filterChain.doFilter(request, response);
    }

    private void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "private, no-store");
        objectMapper.writeValue(response.getWriter(), Map.of("error", message));
    }
}
