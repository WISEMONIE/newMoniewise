package com.moniewise.moniewise_backend.exception;

import com.moniewise.moniewise_backend.dto.response.ApiErrorResponse;
import com.moniewise.moniewise_backend.utils.ExceptionClassifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(TncAcceptanceRequiredException.class)
    public ResponseEntity<?> handleTncAcceptance(TncAcceptanceRequiredException ex) {
        return ResponseEntity.status(403).body(Map.of("error", ex.getMessage(), "tnc", ex.getTncContent(), "version", ex.getTncVersion()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getDefaultMessage())
                .findFirst()
                .orElse("Validation failed");
        return ResponseEntity.badRequest().body(Map.of(
                "status", HttpStatus.BAD_REQUEST.value(),
                "error", message,
                "message", message
        ));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of(
                "status", HttpStatus.BAD_REQUEST.value(),
                "error", ex.getMessage(),
                "message", ex.getMessage()
        ));
    }

    @ExceptionHandler(OtpVerificationException.class)
    public ResponseEntity<Map<String, Object>> handleOtpVerificationException(OtpVerificationException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "status", HttpStatus.BAD_REQUEST.value(),
                "error", ex.getMessage(),
                "message", ex.getMessage()
        ));
    }

    @ExceptionHandler(TooManyRequestsException.class)
    public ResponseEntity<Map<String, Object>> handleTooManyRequests(TooManyRequestsException ex) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Retry-After", String.valueOf(ex.getRetryAfterSeconds()));
        return ResponseEntity
                .status(HttpStatus.TOO_MANY_REQUESTS)
                .headers(headers)
                .body(Map.of(
                        "error", ex.getMessage(),
                        "retryAfterSeconds", ex.getRetryAfterSeconds(),
                        "timestamp", LocalDateTime.now()
                ));
    }

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<ApiErrorResponse> handleSecurity(SecurityException ex, WebRequest request) {
        ApiErrorResponse response = new ApiErrorResponse(HttpStatus.FORBIDDEN.value(), HttpStatus.FORBIDDEN.getReasonPhrase(), "You are not allowed to perform this action.", request.getDescription(false).replace("uri=", ""), LocalDateTime.now());
        return new ResponseEntity<>(response, HttpStatus.FORBIDDEN);
    }

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleEntityNotFound(EntityNotFoundException ex, WebRequest request) {
        ApiErrorResponse response = new ApiErrorResponse(HttpStatus.NOT_FOUND.value(), HttpStatus.NOT_FOUND.getReasonPhrase(), ex.getMessage(), request.getDescription(false).replace("uri=", ""), LocalDateTime.now());
        return new ResponseEntity<>(response, HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalState(IllegalStateException ex, WebRequest request) {
        ApiErrorResponse response = new ApiErrorResponse(HttpStatus.BAD_REQUEST.value(), HttpStatus.BAD_REQUEST.getReasonPhrase(), ex.getMessage(), request.getDescription(false).replace("uri=", ""), LocalDateTime.now());
        return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ApiErrorResponse> handleBadRequest(BadRequestException ex, WebRequest request) {
        ApiErrorResponse errorResponse = new ApiErrorResponse(HttpStatus.BAD_REQUEST.value(), HttpStatus.BAD_REQUEST.getReasonPhrase(), ex.getMessage(), request.getDescription(false).replace("uri=", ""), LocalDateTime.now());
        return new ResponseEntity<>(errorResponse, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(InsufficientFundsException.class)
    public ResponseEntity<Map<String, Object>> handleInsufficientFunds(InsufficientFundsException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "Insufficient Funds");
        body.put("code", "INSUFFICIENT_BUDGET_CREATION_FUNDS");
        body.put("message", ex.getMessage());
        body.put("timestamp", LocalDateTime.now());
        body.putAll(ex.getDetails());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(WalletProvisioningException.class)
    public ResponseEntity<Map<String, Object>> handleWalletProvisioning(WalletProvisioningException ex) {
        return ResponseEntity.status(ex.getStatus()).body(Map.of(
                "status", ex.getStatus().value(),
                "code", ex.getCode(),
                "error", ex.getUserMessage(),
                "message", ex.getUserMessage(),
                "timestamp", LocalDateTime.now()
        ));
    }

    @ExceptionHandler({DataAccessResourceFailureException.class, CannotCreateTransactionException.class})
    public ResponseEntity<Map<String, Object>> handleDatabaseTemporarilyUnavailable(Exception ex, WebRequest request) {
        String correlationId = UUID.randomUUID().toString();
        logger.warn("Database temporarily unavailable [{}] on {}: {}",
                correlationId, request.getDescription(false), ExceptionClassifier.rootCauseMessage(ex));
        HttpHeaders headers = new HttpHeaders();
        headers.set("Retry-After", "5");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .headers(headers)
                .body(Map.of(
                        "status", HttpStatus.SERVICE_UNAVAILABLE.value(),
                        "code", "DATABASE_TEMPORARILY_UNAVAILABLE",
                        "error", "Service is temporarily busy. Please try again.",
                        "message", "Service is temporarily busy. Please try again.",
                        "correlationId", correlationId,
                        "timestamp", LocalDateTime.now()
                ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex, WebRequest request) {
        if (ExceptionClassifier.isTransientConnectionError(ex)) {
            return handleDatabaseTemporarilyUnavailable(ex, request);
        }
        String correlationId = UUID.randomUUID().toString();
        logger.error("Unhandled exception [{}] on {}", correlationId, request.getDescription(false), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "An unexpected error occurred. Please try again.", "correlationId", correlationId, "timestamp", LocalDateTime.now()));
    }
}
