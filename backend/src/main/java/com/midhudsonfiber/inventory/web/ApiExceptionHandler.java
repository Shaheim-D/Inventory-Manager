package com.midhudsonfiber.inventory.web;

import jakarta.persistence.OptimisticLockException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiExceptions.NotFoundException.class)
    public ResponseEntity<?> notFound(ApiExceptions.NotFoundException ex) {
        return body(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(ApiExceptions.BadRequestException.class)
    public ResponseEntity<?> badRequest(ApiExceptions.BadRequestException ex) {
        return body(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(ApiExceptions.ConflictException.class)
    public ResponseEntity<?> conflict(ApiExceptions.ConflictException ex) {
        return body(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(ApiExceptions.UnauthenticatedException.class)
    public ResponseEntity<?> unauthenticated(ApiExceptions.UnauthenticatedException ex) {
        return body(HttpStatus.UNAUTHORIZED, ex.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<?> accessDenied(AccessDeniedException ex) {
        return body(HttpStatus.FORBIDDEN, "You do not have permission to do that.");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> validation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + " " + e.getDefaultMessage())
                .findFirst()
                .orElse("Invalid request");
        return body(HttpStatus.BAD_REQUEST, message);
    }

    @ExceptionHandler({OptimisticLockException.class, OptimisticLockingFailureException.class})
    public ResponseEntity<?> optimisticLock(Exception ex) {
        return body(HttpStatus.CONFLICT,
                "Someone else changed this record while you were editing. Reload and try again.");
    }

    /**
     * The database constraints and triggers built in V1-V9 are a real part of the
     * validation surface, not a backstop -- the PO over-receipt trigger in
     * particular raises a message meant to be shown to a person, so it is passed
     * through rather than flattened into a generic failure.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<?> dataIntegrity(DataIntegrityViolationException ex) {
        String detail = rootMessage(ex);
        log.warn("Database constraint rejected a write: {}", detail);
        return body(HttpStatus.CONFLICT, translate(detail));
    }

    private static String translate(String detail) {
        if (detail == null) return "That change conflicts with an existing record.";
        if (detail.contains("uq_asset_serial")) {
            return "Another asset already uses that serial number.";
        }
        if (detail.contains("asset_category_name_key")) {
            return "A category with that name already exists.";
        }
        if (detail.contains("app_user_username_key")) {
            return "That username is already taken.";
        }
        if (detail.contains("asset_check")) {
            return "Assignee type does not match the assignee value supplied.";
        }
        return detail;
    }

    private static String rootMessage(Throwable ex) {
        Throwable cause = ex;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        return cause.getMessage();
    }

    private static ResponseEntity<Map<String, String>> body(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message == null ? status.getReasonPhrase() : message));
    }
}
