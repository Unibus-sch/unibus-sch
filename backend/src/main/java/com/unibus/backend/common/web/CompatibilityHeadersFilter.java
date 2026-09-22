package com.unibus.backend.common.web;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class CompatibilityHeadersFilter extends OncePerRequestFilter {

    private static final String AUTH_TOKEN_HEADER = "X-Auth-Token";

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        applyHeaders(request, response);
        try {
            filterChain.doFilter(request, response);
        } finally {
            // Apply again for normal MVC responses that may add CORS or security headers.
            // The pre-chain application covers authentication filters that commit early.
            applyHeaders(request, response);
        }
    }

    private void applyHeaders(HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Vary", varyWithAuthToken(response));
        String token = request.getHeader(AUTH_TOKEN_HEADER);
        if (token != null && !token.isBlank()) {
            response.setHeader("Cache-Control", "private, no-store");
        }
    }

    private String varyWithAuthToken(HttpServletResponse response) {
        Set<String> values = new LinkedHashSet<>();
        for (String header : response.getHeaders("Vary")) {
            Arrays.stream(header.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .forEach(values::add);
        }
        boolean alreadyPresent = values.stream()
            .anyMatch(value -> AUTH_TOKEN_HEADER.toLowerCase(Locale.ROOT)
                .equals(value.toLowerCase(Locale.ROOT)));
        if (!alreadyPresent) {
            values.add(AUTH_TOKEN_HEADER);
        }
        return String.join(", ", values);
    }
}
