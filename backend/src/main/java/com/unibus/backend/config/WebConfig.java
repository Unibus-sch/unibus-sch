package com.unibus.backend.config;

import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private static final List<String> ALLOWED_METHODS = List.of("GET", "POST", "PUT", "DELETE", "OPTIONS");
    private static final List<String> ALLOWED_HEADERS = List.of(
        "authorization",
        "x-client-info",
        "apikey",
        "content-type",
        "x-auth-token"
    );

    private final CorsProperties corsProperties;

    public WebConfig(CorsProperties corsProperties) {
        this.corsProperties = corsProperties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        var registration = registry.addMapping("/**")
            .allowedMethods(ALLOWED_METHODS.toArray(String[]::new))
            .allowedHeaders(ALLOWED_HEADERS.toArray(String[]::new))
            .maxAge(86_400);
        if (!corsProperties.getAllowedOrigins().isEmpty()) {
            registration.allowedOrigins(corsProperties.getAllowedOrigins().toArray(String[]::new));
        }
        if (!corsProperties.getAllowedOriginPatterns().isEmpty()) {
            registration.allowedOriginPatterns(
                corsProperties.getAllowedOriginPatterns().toArray(String[]::new)
            );
        }
    }
}
