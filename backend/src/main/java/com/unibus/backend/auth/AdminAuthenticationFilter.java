package com.unibus.backend.auth;

import java.io.IOException;
import java.util.Map;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

@Component
public class AdminAuthenticationFilter extends OncePerRequestFilter {

    public static final String ADMIN_USER_ATTRIBUTE = "unibus.admin.user";
    private static final Pattern ITEM_PATH = Pattern.compile("^/(notices|routes|buses)/[^/]+$");
    private static final Pattern ROUTE_PREVIEW_PATH = Pattern.compile("^/routes/[^/]+/path/preview$");
    private static final Pattern BUS_FORCE_STOP_PATH = Pattern.compile("^/buses/[^/]+/force-stop$");
    private static final Pattern USER_PATH = Pattern.compile("^/users(?:/[^/]+(?:/role)?)?$");
    private static final Pattern REPORT_PATH = Pattern.compile("^/reports(?:/[^/]+)?$");

    private final SessionAuthenticator sessionAuthenticator;
    private final ObjectMapper objectMapper;

    public AdminAuthenticationFilter(
        SessionAuthenticator sessionAuthenticator,
        ObjectMapper objectMapper
    ) {
        this.sessionAuthenticator = sessionAuthenticator;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !isAdminRoute(request.getMethod(), normalizePath(request.getRequestURI()));
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
        if (!"admin".equals(validation.user().role())) {
            writeError(response, 403, "Forbidden: Admin access required");
            return;
        }

        request.setAttribute(ADMIN_USER_ATTRIBUTE, validation.user());
        filterChain.doFilter(request, response);
    }

    private boolean isAdminRoute(String method, String path) {
        return switch (method) {
            case "GET" -> "/users".equals(path) || "/reports".equals(path)
                || "/notifications/history".equals(path);
            case "POST" -> "/notices".equals(path) || "/notices/images".equals(path)
                || "/routes".equals(path) || ROUTE_PREVIEW_PATH.matcher(path).matches()
                || "/buses".equals(path) || BUS_FORCE_STOP_PATH.matcher(path).matches()
                || "/notifications/send".equals(path) || "/notifications/send-existing".equals(path);
            case "PUT" -> ITEM_PATH.matcher(path).matches()
                || USER_PATH.matcher(path).matches() || REPORT_PATH.matcher(path).matches();
            case "DELETE" -> ITEM_PATH.matcher(path).matches();
            default -> false;
        };
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
