package com.simform.resilience.exception;

/**
 * Thrown when a downstream service is temporarily unavailable.
 * This exception is eligible for retry and circuit breaker recording.
 */
public class ServiceUnavailableException extends RuntimeException {

    public ServiceUnavailableException(String message) {
        super(message);
    }

    public ServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
