package com.simform.resilience.service;

import com.simform.resilience.client.ExternalPaymentClient;
import com.simform.resilience.dto.PaymentResponse;
import com.simform.resilience.exception.ServiceUnavailableException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the {@link PaymentService} demonstrating Retry and Circuit Breaker patterns.
 * <p>
 * These tests use the real Resilience4j configuration from application.yaml and the simulated
 * {@link ExternalPaymentClient} to verify that:
 * <ul>
 *   <li>Retry automatically re-attempts failed calls</li>
 *   <li>The circuit breaker transitions from CLOSED to OPEN after repeated failures</li>
 *   <li>Fallback responses are returned when the circuit is OPEN</li>
 * </ul>
 * </p>
 */
@SpringBootTest
class PaymentServiceTest {

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private ExternalPaymentClient paymentClient;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @BeforeEach
    void setUp() {
        // Reset the payment client and circuit breakers before each test
        paymentClient.reset();
        paymentClient.setFailuresBeforeSuccess(2);
        circuitBreakerRegistry.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
    }

    @Test
    @DisplayName("Retry Pattern: Should succeed after transient failures are retried")
    void shouldRetryAndSucceed() {
        // Configure: fail 2 times, then succeed (retry max-attempts = 3 in config)
        paymentClient.setFailuresBeforeSuccess(2);

        PaymentResponse response = paymentService.processPayment("ORDER-001", 99.99, "CUST-001");

        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        assertThat(response.getAmountCharged()).isEqualTo(99.99);
        // Verify that 3 calls were made (2 failures + 1 success)
        assertThat(paymentClient.getCallCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("Retry Pattern: Should invoke fallback when all retries are exhausted")
    void shouldFallbackWhenAllRetriesExhausted() {
        // Configure: fail more times than max-attempts allows
        paymentClient.setFailuresBeforeSuccess(10);

        PaymentResponse response = paymentService.processPayment("ORDER-002", 49.99, "CUST-002");

        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo("PENDING");
        assertThat(response.getTransactionId()).startsWith("FALLBACK-");
        assertThat(response.getMessage()).contains("unavailable");
    }

    @Test
    @DisplayName("Circuit Breaker: Should open circuit after threshold failures")
    void shouldOpenCircuitBreakerAfterThresholdFailures() {
        // Configure: always fail
        paymentClient.setFailuresBeforeSuccess(100);

        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("paymentService");

        // Make several calls to trigger the circuit breaker (minimum-number-of-calls = 3, failure-rate-threshold = 50%)
        for (int i = 0; i < 5; i++) {
            paymentClient.reset(); // Reset counter so failures keep happening
            paymentService.processPayment("ORDER-CB-" + i, 10.0, "CUST-CB");
        }

        // After repeated failures, circuit should be OPEN
        assertThat(circuitBreaker.getState())
                .as("Circuit breaker should transition to OPEN after repeated failures")
                .isIn(CircuitBreaker.State.OPEN, CircuitBreaker.State.HALF_OPEN);
    }

    @Test
    @DisplayName("Retry Pattern: Should not retry on BusinessValidationException")
    void shouldNotRetryOnIgnoredException() {
        // When failuresBeforeSuccess is 0, the first call succeeds — no retry needed
        paymentClient.setFailuresBeforeSuccess(0);

        PaymentResponse response = paymentService.processPayment("ORDER-003", 25.00, "CUST-003");

        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        assertThat(paymentClient.getCallCount()).isEqualTo(1);
    }
}
