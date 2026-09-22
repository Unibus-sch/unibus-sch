package com.unibus.backend.config;

import com.unibus.backend.auth.AdminAuthenticationFilter;
import com.unibus.backend.auth.DriverAuthenticationFilter;
import com.unibus.backend.auth.UserAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain apiSecurity(
        HttpSecurity http,
        AdminAuthenticationFilter adminAuthenticationFilter,
        DriverAuthenticationFilter driverAuthenticationFilter,
        UserAuthenticationFilter userAuthenticationFilter
    ) throws Exception {
        return http
            .cors(Customizer.withDefaults())
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/health",
                    "/actuator/health",
                    "/actuator/health/**"
                ).permitAll()
                .requestMatchers(
                    HttpMethod.GET,
                    "/notices",
                    "/notices/*",
                    "/routes",
                    "/routes/*",
                    "/routes/*/path",
                    "/buses",
                    "/buses/*",
                    "/buses/locations/latest"
                ).permitAll()
                .requestMatchers(
                    HttpMethod.POST,
                    "/auth/signup",
                    "/auth/login",
                    "/auth/kakao",
                    "/auth/logout"
                ).permitAll()
                .requestMatchers("/notices/**", "/routes/**", "/buses/**", "/users/**", "/reports/**",
                    "/notifications/**", "/driver/**").permitAll()
                // Unknown API paths must reach the compatibility 404 handler instead of
                // being converted to Spring Security's empty 403 response.
                .anyRequest().permitAll()
            )
            .addFilterBefore(userAuthenticationFilter, AnonymousAuthenticationFilter.class)
            .addFilterBefore(driverAuthenticationFilter, AnonymousAuthenticationFilter.class)
            .addFilterBefore(adminAuthenticationFilter, AnonymousAuthenticationFilter.class)
            .build();
    }

    @Bean
    FilterRegistrationBean<AdminAuthenticationFilter> disableAdminFilterServletRegistration(
        AdminAuthenticationFilter filter
    ) {
        FilterRegistrationBean<AdminAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    FilterRegistrationBean<DriverAuthenticationFilter> disableDriverFilterServletRegistration(
        DriverAuthenticationFilter filter
    ) {
        FilterRegistrationBean<DriverAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    FilterRegistrationBean<UserAuthenticationFilter> disableUserFilterServletRegistration(
        UserAuthenticationFilter filter
    ) {
        FilterRegistrationBean<UserAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(BCryptPasswordEncoder.BCryptVersion.$2B, 10);
    }
}
