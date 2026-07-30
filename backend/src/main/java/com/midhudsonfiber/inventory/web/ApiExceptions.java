package com.midhudsonfiber.inventory.web;

public final class ApiExceptions {
    private ApiExceptions() {}

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) { super(message); }
    }

    public static class BadRequestException extends RuntimeException {
        public BadRequestException(String message) { super(message); }
    }

    public static class ConflictException extends RuntimeException {
        public ConflictException(String message) { super(message); }
    }

    public static class UnauthenticatedException extends RuntimeException {
        public UnauthenticatedException(String message) { super(message); }
    }
}
