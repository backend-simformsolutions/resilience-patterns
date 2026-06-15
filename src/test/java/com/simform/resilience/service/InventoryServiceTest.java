package com.simform.resilience.service;

import com.simform.resilience.client.ExternalInventoryClient;
import com.simform.resilience.dto.InventoryResponse;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the {@link InventoryService} demonstrating Timeout and Circuit Breaker patterns.
 * <p>
 * These tests verify that:
 * <ul>
 *   <li>The TimeLimiter cuts off slow responses and the fallback is invoked</li>
 *   <li>The circuit breaker opens after repeated failures from the inventory service</li>
 *   <li>Successful calls return the expected inventory data</li>
 * </ul>
 * </p>
 */
@SpringBootTest
class InventoryServiceTest {

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private ExternalInventoryClient inventoryClient;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @BeforeEach
    void setUp() {
        inventoryClient.reset();
        circuitBreakerRegistry.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
    }

    @Test
    @DisplayName("Happy Path: Should return inventory when service responds normally")
    void shouldReturnInventoryWhenServiceIsHealthy() throws ExecutionException, InterruptedException {
        // No delays or failures configured
        CompletableFuture<InventoryResponse> future = inventoryService.checkInventory("PROD-001", 5);
        InventoryResponse response = future.get();

        assertThat(response).isNotNull();
        assertThat(response.isAvailable()).isTrue();
        assertThat(response.getStatus()).isEqualTo("RESERVED");
        assertThat(response.getReservedQuantity()).isEqualTo(5);
    }

    @Test
    @DisplayName("Timeout Pattern: Should invoke fallback when service is too slow")
    void shouldTimeoutAndFallbackWhenServiceIsSlow() throws ExecutionException, InterruptedException {
        // Configure a delay longer than the timeout-duration (2s in config)
        inventoryClient.setSimulateDelay(true);
        inventoryClient.setDelayMillis(5000);

        CompletableFuture<InventoryResponse> future = inventoryService.checkInventory("PROD-002", 3);
        InventoryResponse response = future.get();

        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo("UNKNOWN");
        assertThat(response.isAvailable()).isFalse();
        assertThat(response.getMessage()).contains("unavailable");
    }

    @Test
    @DisplayName("Circuit Breaker: Should open circuit after repeated inventory failures")
    void shouldOpenCircuitBreakerAfterRepeatedFailures() throws ExecutionException, InterruptedException {
        // Configure the inventory service to always fail
        inventoryClient.setSimulateFailure(true);

        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("inventoryService");

        // Make enough calls to trigger the circuit breaker
        for (int i = 0; i < 10; i++) {
            CompletableFuture<InventoryResponse> future = inventoryService.checkInventory("PROD-CB-" + i, 1);
            future.get(); // The fallback should handle the failure
        }

        // After repeated failures, the circuit should be OPEN
        assertThat(circuitBreaker.getState())
                .as("Circuit breaker should transition to OPEN after repeated failures")
                .isIn(CircuitBreaker.State.OPEN, CircuitBreaker.State.HALF_OPEN);
    }

    @Test
    @DisplayName("Circuit Breaker: Fallback should return safe default when circuit is open")
    void shouldReturnFallbackResponseWhenCircuitIsOpen() throws ExecutionException, InterruptedException {
        // Force circuit breaker OPEN
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("inventoryService");
        circuitBreaker.transitionToOpenState();

        CompletableFuture<InventoryResponse> future = inventoryService.checkInventory("PROD-003", 2);
        InventoryResponse response = future.get();

        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo("UNKNOWN");
        assertThat(response.isAvailable()).isFalse();
    }
}
