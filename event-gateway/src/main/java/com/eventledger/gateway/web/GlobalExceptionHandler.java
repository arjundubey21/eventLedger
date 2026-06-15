package com.eventledger.gateway.web;

import com.eventledger.gateway.client.AccountServiceUnavailableException;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {

    /** Bean Validation failures (missing fields, non-positive amount, etc.) -> 400. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        List<String> details = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .sorted()
                .toList();
        return build(HttpStatus.BAD_REQUEST, "Validation failed", details);
    }

    /** Malformed JSON or an unknown transaction type -> 400. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException ex) {
        String message = "Malformed request body";
        if (ex.getCause() instanceof InvalidFormatException ife
                && ife.getTargetType() != null && ife.getTargetType().isEnum()) {
            message = "Invalid value '" + ife.getValue() + "'. Allowed: CREDIT, DEBIT";
        }
        return build(HttpStatus.BAD_REQUEST, message, List.of());
    }

    /** Account Service unreachable / circuit open -> 503 (not a 500, and no hanging). */
    @ExceptionHandler(AccountServiceUnavailableException.class)
    public ResponseEntity<ApiError> handleUnavailable(AccountServiceUnavailableException ex) {
        return build(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), List.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleGeneric(Exception ex) {
        return build(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage(), List.of());
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String message, List<String> details) {
        return ResponseEntity.status(status).body(new ApiError(
                Instant.now(), status.value(), status.getReasonPhrase(), message, details));
    }
}
