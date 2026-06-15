package com.eventledger.gateway.web;

import com.eventledger.gateway.client.AccountServiceUnavailableException;
import com.eventledger.gateway.service.EventConflictException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.HttpClientErrorException;

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
        Throwable cause = ex.getMostSpecificCause();
        if (cause != null && cause.getMessage() != null
                && cause.getMessage().contains("TransactionType")) {
            message = "Invalid 'type'. Allowed values: CREDIT, DEBIT";
        }
        return build(HttpStatus.BAD_REQUEST, message, List.of());
    }

    /** Account Service unreachable / circuit open / bulkhead full -> 503 (not a 500, no hanging). */
    @ExceptionHandler(AccountServiceUnavailableException.class)
    public ResponseEntity<ApiError> handleUnavailable(AccountServiceUnavailableException ex) {
        return build(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), List.of());
    }

    /** Same eventId resubmitted with a different payload -> 409. */
    @ExceptionHandler(EventConflictException.class)
    public ResponseEntity<ApiError> handleConflict(EventConflictException ex) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), List.of());
    }

    /** A 4xx from the Account Service (e.g. a currency conflict) is surfaced with the same status. */
    @ExceptionHandler(HttpClientErrorException.class)
    public ResponseEntity<ApiError> handleDownstreamClientError(HttpClientErrorException ex) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        if (status == null) {
            status = HttpStatus.BAD_GATEWAY;
        }
        return build(status, "Account Service rejected the request: " + ex.getStatusText(), List.of());
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
