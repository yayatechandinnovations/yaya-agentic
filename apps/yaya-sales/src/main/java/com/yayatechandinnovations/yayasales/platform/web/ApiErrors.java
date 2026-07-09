package com.yayatechandinnovations.yayasales.platform.web;

/**
 * Module-neutral API failures. Controllers throw these; the advice maps them
 * to status codes so every module reports errors with one shape.
 */
public final class ApiErrors {

    private ApiErrors() {}

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String what, String id) {
            super(what + " not found: " + id);
        }
    }

    public static class ConflictException extends RuntimeException {
        public ConflictException(String message) {
            super(message);
        }
    }

    public static class UnprocessableException extends RuntimeException {
        public UnprocessableException(String message) {
            super(message);
        }
    }
}
