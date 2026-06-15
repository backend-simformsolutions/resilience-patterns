package com.simform.resilience.service;

import com.simform.resilience.client.ExternalInventoryClient;
import com.simform.resilience.dto.InventoryResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

/**
 * Service responsible for inventory management with resilience patterns applied.
 * <p>
 * Patterns demonstrated:
 * <ul>
 *   <li><strong>Timeout (TimeLimiter)</strong> — Limits how long the caller waits for a response.
 *       If the external inventory service does not respond within the configured duration,
 *       a {@code TimeoutException} is thrown and the fallback is invoked.</li>
 *   <li><strong>Circuit Breaker</strong> — Monitors the failure rate (including timeouts) and
 *       opens the circuit when the threshold is breached.</li>
 * </ul>
 * </p>
 * <p>
 * <strong>Note:</strong> The {@code @TimeLimiter} annotation requires the method to return
 * a {@link CompletableFuture}. This is a framework requirement so that the TimeLimiter
 * can cancel the underlying task if it exceeds the allowed duration.
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryService {

    private final ExternalInventoryClient externalInventoryClient;

    /**
     * Checks inventory availability with Timeout + Circuit Breaker protection.
     * <p>
     * Flow:
     * <ol>
     *   <li>The call is wrapped in a {@link CompletableFuture} for async timeout control.</li>
     *   <li>The TimeLimiter enforces a maximum wait duration (configured in application.yaml).</li>
     *   <li>If the inventory service responds within time, the result is returned.</li>
     *   <li>If it exceeds the timeout, the future is cancelled and the fallback is invoked.</li>
     *   <li>Failures (including timeouts) are recorded by the circuit breaker.</li>
     * </ol>
     * </p>
     *
     * @param productId the product identifier to check
     * @param quantity  the required quantity
     * @return a {@link CompletableFuture} wrapping the {@link InventoryResponse}
     */
    @TimeLimiter(name = "inventoryService")
    @CircuitBreaker(name = "inventoryService", fallbackMethod = "inventoryFallback")
    public CompletableFuture<InventoryResponse> checkInventory(String productId, int quantity) {
        log.info("Checking inventory for productId={}, quantity={}", productId, quantity);
        return CompletableFuture.supplyAsync(() ->
                externalInventoryClient.checkInventory(productId, quantity)
        );
    }

    /**
     * Fallback method invoked when the inventory check times out or the circuit is open.
     * <p>
     * Only {@code @CircuitBreaker} has the fallback — it is the outermost decorator.
     * {@code @TimeLimiter} does NOT have a fallback, so timeout exceptions propagate
     * up to the circuit breaker which records them and invokes this fallback.
     * <p>
     * In a real system, this could:
     * <ul>
     *   <li>Return cached inventory data</li>
     *   <li>Return a "check later" response</li>
     *   <li>Route to a secondary warehouse</li>
     * </ul>
     * </p>
     *
     * @param productId the product identifier
     * @param quantity  the requested quantity
     * @param throwable the exception that triggered the fallback
     * @return a fallback {@link CompletableFuture} wrapping an {@link InventoryResponse}
     */
    private CompletableFuture<InventoryResponse> inventoryFallback(String productId, int quantity, Throwable throwable) {
        log.warn("Inventory fallback triggered for productId={}. Reason: {}", productId, throwable.getMessage());
        InventoryResponse fallbackResponse = InventoryResponse.builder()
                .productId(productId)
                .available(false)
                .reservedQuantity(0)
                .status("UNKNOWN")
                .message("Inventory service is currently unavailable. Please try again later. Reason: "
                        + throwable.getMessage())
                .build();
        return CompletableFuture.completedFuture(fallbackResponse);
    }
}
