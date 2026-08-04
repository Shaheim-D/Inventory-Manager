package com.midhudsonfiber.inventory.web;

public final class ApiExceptions {
    private ApiExceptions() {}

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) { super(message); }
    }

    public static class BadRequestException extends RuntimeException {
        public BadRequestException(String message) { super(message); }
    }

    /**
     * Authenticated, but not allowed to do this particular thing. Distinct from
     * a permission check: this is "that request belongs to someone else", which
     * no permission key can express because it depends on the row.
     */
    public static class ForbiddenException extends RuntimeException {
        public ForbiddenException(String message) { super(message); }
    }

    public static class ConflictException extends RuntimeException {
        public ConflictException(String message) { super(message); }
    }

    public static class UnauthenticatedException extends RuntimeException {
        public UnauthenticatedException(String message) { super(message); }
    }
}
