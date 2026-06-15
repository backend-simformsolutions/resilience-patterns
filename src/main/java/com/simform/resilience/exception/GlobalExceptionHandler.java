package com.simform.resilience.exception;

import com.simform.resilience.dto.OrderResponse;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.concurrent.TimeoutException;

/**
 * Centralized exception handler that translates resilience-related exceptions
 * into meaningful HTTP responses.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(CallNotPermittedException.class)
    public ResponseEntity<OrderResponse> handleCircuitBreakerOpen(CallNotPermittedException ex) {
        log.warn("Circuit breaker is OPEN — rejecting call: {}", ex.getMessage());
        OrderResponse response = OrderResponse.builder()
                .status("REJECTED")
                .message("Service is temporarily unavailable. Circuit breaker is OPEN. Please try again later.")
                .timestamp(LocalDateTime.now())
                .build();
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
    }

    @ExceptionHandler(TimeoutException.class)
    public ResponseEntity<OrderResponse> handleTimeout(TimeoutException ex) {
        log.warn("Request timed out: {}", ex.getMessage());
        OrderResponse response = OrderResponse.builder()
                .status("TIMEOUT")
                .message("The request timed out. The downstream service did not respond within the allowed duration.")
                .timestamp(LocalDateTime.now())
                .build();
        return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(response);
    }

    @ExceptionHandler(ServiceUnavailableException.class)
    public ResponseEntity<OrderResponse> handleServiceUnavailable(ServiceUnavailableException ex) {
        log.error("Service unavailable after all retries: {}", ex.getMessage());
        OrderResponse response = OrderResponse.builder()
                .status("FAILED")
                .message("Downstream service is unavailable: " + ex.getMessage())
                .timestamp(LocalDateTime.now())
                .build();
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
    }

    @ExceptionHandler(BusinessValidationException.class)
    public ResponseEntity<OrderResponse> handleBusinessValidation(BusinessValidationException ex) {
        log.warn("Business validation failed: {}", ex.getMessage());
        OrderResponse response = OrderResponse.builder()
                .status("VALIDATION_FAILED")
                .message(ex.getMessage())
                .timestamp(LocalDateTime.now())
                .build();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<OrderResponse> handleGenericException(Exception ex) {
        log.error("Unexpected error occurred: {}", ex.getMessage(), ex);
        OrderResponse response = OrderResponse.builder()
                .status("ERROR")
                .message("An unexpected error occurred: " + ex.getMessage())
                .timestamp(LocalDateTime.now())
                .build();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }
}
