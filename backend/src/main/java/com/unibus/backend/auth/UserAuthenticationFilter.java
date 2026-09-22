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
public class UserAuthenticationFilter extends OncePerRequestFilter {

    public static final String AUTHENTICATED_USER_ATTRIBUTE = "unibus.authenticated.user";

    private final SessionAuthenticator sessionAuthenticator;
    private final ObjectMapper objectMapper;

    public UserAuthenticationFilter(
        SessionAuthenticator sessionAuthenticator,
        ObjectMapper objectMapper
    ) {
        this.sessionAuthenticator = sessionAuthenticator;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!"POST".equals(request.getMethod())) return true;
        String path = normalizePath(request.getRequestURI());
        return !"/notifications/subscribe".equals(path)
            && !"/notifications/unsubscribe".equals(path);
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

        request.setAttribute(AUTHENTICATED_USER_ATTRIBUTE, validation.user());
        filterChain.doFilter(request, response);
    }

    private String normalizePath(String path) {
        if (path != null && path.length() > 1 && path.endsWith("/")) {
            return path.substring(0, path.length() - 1);
        }
        return path;
    }

    private void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "private, no-store");
        objectMapper.writeValue(response.getWriter(), Map.of("error", message));
    }
}
