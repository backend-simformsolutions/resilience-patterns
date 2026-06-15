package com.simform.resilience.exception;

/**
 * Thrown when a business validation rule is violated (e.g., insufficient funds, invalid product).
 * This exception is NOT eligible for retry — retrying won't fix a validation error.
 */
public class BusinessValidationException extends RuntimeException {

    public BusinessValidationException(String message) {
        super(message);
    }

    public BusinessValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
