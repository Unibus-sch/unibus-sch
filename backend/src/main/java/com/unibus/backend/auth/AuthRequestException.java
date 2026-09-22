package com.unibus.backend.auth;

import org.springframework.http.HttpStatus;

final class AuthRequestException extends RuntimeException {

    private final HttpStatus status;

    AuthRequestException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    HttpStatus status() {
        return status;
    }
}
