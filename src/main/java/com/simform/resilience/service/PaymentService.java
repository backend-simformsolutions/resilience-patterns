package com.simform.resilience.service;

import com.simform.resilience.client.ExternalPaymentClient;
import com.simform.resilience.dto.PaymentResponse;
import com.simform.resilience.exception.ServiceUnavailableException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Service responsible for payment processing with resilience patterns applied.
 * <p>
 * Patterns demonstrated:
 * <ul>
 *   <li><strong>Retry</strong> — Automatically retries failed calls to the external payment
 *       gateway up to the configured number of attempts.</li>
 *   <li><strong>Circuit Breaker</strong> — Monitors the failure rate and opens the circuit
 *       when the threshold is breached, preventing further calls to the failing service.
 *       Falls back to {@link #paymentFallback} when the circuit is open.</li>
 * </ul>
 * </p>
 * <p>
 * <strong>Annotation ordering:</strong> Resilience4j applies decorators in a specific order.
     * {@code @Retry} wraps {@code @CircuitBreaker}, meaning the retry is the outermost decorator.
     * The circuit breaker records each failure, and the retry re-attempts the call.
     * Only {@code @Retry} has a fallback — if {@code @CircuitBreaker} also had one, it would
     * swallow the exception and the retry would never trigger.
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final ExternalPaymentClient externalPaymentClient;

    /**
     * Processes a payment with Retry + Circuit Breaker protection.
     * <p>
     * Flow:
     * <ol>
     *   <li>Circuit breaker checks whether calls are allowed (CLOSED or HALF_OPEN state).</li>
     *   <li>If allowed, the call is attempted up to {@code max-attempts} times (retry).</li>
     *   <li>Each failed attempt is recorded by the circuit breaker's sliding window.</li>
     *   <li>If the failure rate exceeds the threshold, the circuit transitions to OPEN.</li>
     *   <li>While OPEN, calls are short-circuited and routed to {@link #paymentFallback}.</li>
     * </ol>
     * </p>
     *
     * @param orderId    the order identifier
     * @param amount     the payment amount
     * @param customerId the customer identifier
     * @return a successful or fallback {@link PaymentResponse}
     */
    @CircuitBreaker(name = "paymentService")
    @Retry(name = "paymentService", fallbackMethod = "paymentFallback")
    public PaymentResponse processPayment(String orderId, double amount, String customerId) {
        log.info("Initiating payment for orderId={}, amount={}, customerId={}", orderId, amount, customerId);
        return externalPaymentClient.processPayment(orderId, amount, customerId);
    }

    /**
     * Fallback method invoked when all retries are exhausted OR the circuit breaker rejects the call.
     * <p>
     * This is the single fallback for both patterns. Because {@code @Retry} is the outermost
     * decorator, its fallback is the last line of defense.
     * <p>
     * In a real system, this could:
     * <ul>
     *   <li>Queue the payment for asynchronous processing</li>
     *   <li>Return a "pending" status to the client</li>
     *   <li>Trigger an alert to the operations team</li>
     * </ul>
     * </p>
     *
     * @param orderId    the order identifier
     * @param amount     the payment amount
     * @param customerId the customer identifier
     * @param throwable  the exception that triggered the fallback
     * @return a fallback {@link PaymentResponse} with a PENDING status
     */
    private PaymentResponse paymentFallback(String orderId, double amount, String customerId, Throwable throwable) {
        log.warn("Payment fallback triggered for orderId={}. Reason: {}", orderId, throwable.getMessage());
        return PaymentResponse.builder()
                .transactionId("FALLBACK-" + orderId)
                .status("PENDING")
                .amountCharged(0.0)
                .message("Payment service is currently unavailable. Your payment has been queued for processing. Reason: "
                        + throwable.getMessage())
                .build();
    }
}
