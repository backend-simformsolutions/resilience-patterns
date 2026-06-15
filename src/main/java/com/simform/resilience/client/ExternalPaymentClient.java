package com.simform.resilience.client;

import com.simform.resilience.dto.PaymentResponse;
import com.simform.resilience.exception.ServiceUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simulates an external Payment Gateway.
 * <p>
 * In a real-world application, this would be a REST client (e.g., WebClient or RestTemplate)
 * calling a third-party payment API. For this POC, we simulate intermittent failures
 * to demonstrate how Retry and Circuit Breaker patterns handle transient errors.
 * </p>
 * <p>
 * Behavior:
 * <ul>
 *   <li>The first N calls (configurable via {@code failuresBeforeSuccess}) will throw
 *       {@link ServiceUnavailableException} to simulate transient failures.</li>
 *   <li>Subsequent calls succeed, returning a valid {@link PaymentResponse}.</li>
 *   <li>The counter can be reset to re-simulate the failure cycle.</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
public class ExternalPaymentClient {

    private final AtomicInteger callCounter = new AtomicInteger(0);
    private volatile int failuresBeforeSuccess = 2;

    /**
     * Simulates a payment processing call to an external gateway.
     *
     * @param orderId    the order identifier
     * @param amount     the payment amount
     * @param customerId the customer identifier
     * @return a successful {@link PaymentResponse} after transient failures
     * @throws ServiceUnavailableException if the simulated service is "down"
     */
    public PaymentResponse processPayment(String orderId, double amount, String customerId) {
        int attempt = callCounter.incrementAndGet();
        log.info("[PaymentClient] Attempt #{} — Processing payment for order: {}, amount: {}", attempt, orderId, amount);

        if (attempt <= failuresBeforeSuccess) {
            log.warn("[PaymentClient] Attempt #{} — Payment Gateway is DOWN (simulated failure)", attempt);
            throw new ServiceUnavailableException(
                    "Payment Gateway is temporarily unavailable (attempt " + attempt + ")");
        }

        log.info("[PaymentClient] Attempt #{} — Payment processed successfully for order: {}", attempt, orderId);
        return PaymentResponse.builder()
                .transactionId(UUID.randomUUID().toString())
                .status("SUCCESS")
                .amountCharged(amount)
                .message("Payment processed successfully for customer " + customerId)
                .build();
    }

    /**
     * Configures how many consecutive calls should fail before succeeding.
     * Useful for testing different retry scenarios.
     *
     * @param failures the number of failures before a successful response
     */
    public void setFailuresBeforeSuccess(int failures) {
        this.failuresBeforeSuccess = failures;
        log.info("[PaymentClient] Configured to fail {} time(s) before succeeding", failures);
    }

    /**
     * Resets the internal call counter so the failure cycle starts over.
     */
    public void reset() {
        callCounter.set(0);
        log.info("[PaymentClient] Call counter reset — failure cycle restarted");
    }

    /**
     * Returns the current call count for observability/testing.
     */
    public int getCallCount() {
        return callCounter.get();
    }
}
