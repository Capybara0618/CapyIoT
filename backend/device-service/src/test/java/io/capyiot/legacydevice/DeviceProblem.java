package io.capyiot.legacydevice;

import org.springframework.http.HttpStatus;

class DeviceProblem extends RuntimeException {
    final HttpStatus status;
    final String code;

    DeviceProblem(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    static DeviceProblem badRequest(String message) { return new DeviceProblem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message); }
    static DeviceProblem conflict(String message) { return new DeviceProblem(HttpStatus.CONFLICT, "VERSION_CONFLICT", message); }
    static DeviceProblem notFound(String message) { return new DeviceProblem(HttpStatus.NOT_FOUND, "NOT_FOUND", message); }
}
