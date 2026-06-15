package com.simform.resilience.client;

import com.simform.resilience.dto.InventoryResponse;
import com.simform.resilience.exception.ServiceUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simulates an external Inventory / Warehouse service.
 * <p>
 * In a real-world application, this would be a REST or gRPC client calling a
 * warehouse management system. For this POC, we simulate slow responses and
 * intermittent outages to demonstrate how Timeout and Circuit Breaker patterns
 * protect the caller.
 * </p>
 * <p>
 * Behavior:
 * <ul>
 *   <li>When {@code simulateDelay} is true, each call sleeps for {@code delayMillis}
 *       milliseconds — used to trigger the TimeLimiter (timeout) pattern.</li>
 *   <li>When {@code simulateFailure} is true, every call throws a
 *       {@link ServiceUnavailableException} — used to trigger the Circuit Breaker.</li>
 *   <li>Otherwise, a successful {@link InventoryResponse} is returned.</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
public class ExternalInventoryClient {

    private final AtomicBoolean simulateDelay = new AtomicBoolean(false);
    private final AtomicBoolean simulateFailure = new AtomicBoolean(false);
    private final AtomicInteger callCounter = new AtomicInteger(0);
    private volatile long delayMillis = 5000L;

    /**
     * Simulates an inventory-check call to an external warehouse system.
     *
     * @param productId the product to check
     * @param quantity  the requested quantity
     * @return a successful {@link InventoryResponse} unless a failure or delay is simulated
     * @throws ServiceUnavailableException if the simulated service is "down"
     */
    public InventoryResponse checkInventory(String productId, int quantity) {
        int attempt = callCounter.incrementAndGet();
        log.info("[InventoryClient] Call #{} — Checking inventory for product: {}, qty: {}", attempt, productId, quantity);

        // Simulate failure scenario (for Circuit Breaker testing)
        if (simulateFailure.get()) {
            log.warn("[InventoryClient] Call #{} — Inventory Service is DOWN (simulated failure)", attempt);
            throw new ServiceUnavailableException(
                    "Inventory Service is temporarily unavailable (call " + attempt + ")");
        }

        // Simulate slow response (for Timeout testing)
        if (simulateDelay.get()) {
            log.warn("[InventoryClient] Call #{} — Inventory Service is SLOW (simulated {}ms delay)", attempt, delayMillis);
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ServiceUnavailableException("Inventory check interrupted", e);
            }
        }

        log.info("[InventoryClient] Call #{} — Inventory available for product: {}", attempt, productId);
        return InventoryResponse.builder()
                .productId(productId)
                .available(true)
                .reservedQuantity(quantity)
                .status("RESERVED")
                .message("Inventory reserved successfully for " + quantity + " unit(s)")
                .build();
    }

    /**
     * Enables or disables simulated delays (for Timeout pattern testing).
     */
    public void setSimulateDelay(boolean enabled) {
        this.simulateDelay.set(enabled);
        log.info("[InventoryClient] Delay simulation {}", enabled ? "ENABLED" : "DISABLED");
    }

    /**
     * Enables or disables simulated failures (for Circuit Breaker testing).
     */
    public void setSimulateFailure(boolean enabled) {
        this.simulateFailure.set(enabled);
        log.info("[InventoryClient] Failure simulation {}", enabled ? "ENABLED" : "DISABLED");
    }

    /**
     * Configures the delay duration in milliseconds.
     */
    public void setDelayMillis(long millis) {
        this.delayMillis = millis;
        log.info("[InventoryClient] Delay set to {}ms", millis);
    }

    /**
     * Resets all simulation state.
     */
    public void reset() {
        callCounter.set(0);
        simulateDelay.set(false);
        simulateFailure.set(false);
        delayMillis = 5000L;
        log.info("[InventoryClient] Reset — all simulations disabled, counter cleared");
    }

    /**
     * Returns the current call count for observability/testing.
     */
    public int getCallCount() {
        return callCounter.get();
    }
}
