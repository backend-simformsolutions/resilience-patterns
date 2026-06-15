package com.simform.resilience.controller;

import com.simform.resilience.client.ExternalInventoryClient;
import com.simform.resilience.client.ExternalPaymentClient;
import com.simform.resilience.dto.ResilienceStatusResponse;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * REST controller for managing simulation settings and observing resilience state.
 * <p>
 * This controller is specific to the POC and provides:
 * <ul>
 *   <li>Endpoints to toggle failure/delay simulation on the external clients</li>
 *   <li>An endpoint to inspect the current state of circuit breakers</li>
 *   <li>A reset endpoint to restore all simulators to their default state</li>
 * </ul>
 * In production, you would use Spring Boot Actuator endpoints for similar observability.
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/simulation")
@RequiredArgsConstructor
public class SimulationController {

    private final ExternalPaymentClient paymentClient;
    private final ExternalInventoryClient inventoryClient;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    // ─── Payment Simulation Controls ─────────────────────────────

    /**
     * Configures how many consecutive payment calls will fail before succeeding.
     * <p>Example: {@code POST /api/v1/simulation/payment/failures?count=5}</p>
     *
     * @param count number of failures before success
     */
    @PostMapping("/payment/failures")
    public ResponseEntity<Map<String, String>> setPaymentFailures(@RequestParam int count) {
        paymentClient.setFailuresBeforeSuccess(count);
        paymentClient.reset();
        return ResponseEntity.ok(Map.of(
                "service", "payment",
                "failuresBeforeSuccess", String.valueOf(count),
                "message", "Payment client configured — next " + count + " call(s) will fail"
        ));
    }

    // ─── Inventory Simulation Controls ───────────────────────────

    /**
     * Toggles the simulated delay on the inventory service.
     * <p>Example: {@code POST /api/v1/simulation/inventory/delay?enabled=true&millis=5000}</p>
     *
     * @param enabled whether to simulate a slow response
     * @param millis  delay duration in milliseconds (default 5000)
     */
    @PostMapping("/inventory/delay")
    public ResponseEntity<Map<String, String>> setInventoryDelay(
            @RequestParam boolean enabled,
            @RequestParam(defaultValue = "5000") long millis) {
        inventoryClient.setSimulateDelay(enabled);
        inventoryClient.setDelayMillis(millis);
        return ResponseEntity.ok(Map.of(
                "service", "inventory",
                "delayEnabled", String.valueOf(enabled),
                "delayMillis", String.valueOf(millis),
                "message", enabled
                        ? "Inventory client will now simulate " + millis + "ms delay"
                        : "Inventory delay simulation disabled"
        ));
    }

    /**
     * Toggles simulated failures on the inventory service.
     * <p>Example: {@code POST /api/v1/simulation/inventory/failure?enabled=true}</p>
     *
     * @param enabled whether every inventory call should fail
     */
    @PostMapping("/inventory/failure")
    public ResponseEntity<Map<String, String>> setInventoryFailure(@RequestParam boolean enabled) {
        inventoryClient.setSimulateFailure(enabled);
        return ResponseEntity.ok(Map.of(
                "service", "inventory",
                "failureEnabled", String.valueOf(enabled),
                "message", enabled
                        ? "Inventory client will now fail on every call"
                        : "Inventory failure simulation disabled"
        ));
    }

    // ─── Resilience State Observability ──────────────────────────

    /**
     * Returns the current state of all registered circuit breakers.
     * <p>
     * Useful for observing how the circuit transitions between CLOSED, OPEN, and HALF_OPEN
     * states as you trigger failures via the simulation endpoints.
     * </p>
     */
    @GetMapping("/status")
    public ResponseEntity<ResilienceStatusResponse> getResilienceStatus() {
        Map<String, String> cbStates = new HashMap<>();
        circuitBreakerRegistry.getAllCircuitBreakers().forEach(cb -> {
            CircuitBreaker.Metrics metrics = cb.getMetrics();
            cbStates.put(cb.getName(), String.format(
                    "state=%s, failureRate=%.1f%%, calls=%d, failed=%d, notPermitted=%d",
                    cb.getState(),
                    metrics.getFailureRate(),
                    metrics.getNumberOfBufferedCalls(),
                    metrics.getNumberOfFailedCalls(),
                    metrics.getNumberOfNotPermittedCalls()
            ));
        });

        ResilienceStatusResponse response = ResilienceStatusResponse.builder()
                .circuitBreakerStates(cbStates)
                .message("Current resilience component states")
                .build();

        return ResponseEntity.ok(response);
    }

    // ─── Reset ───────────────────────────────────────────────────

    /**
     * Resets all simulation state and circuit breakers to their defaults.
     * <p>
     * Call this between test scenarios to start from a clean state.
     * </p>
     */
    @PostMapping("/reset")
    public ResponseEntity<Map<String, String>> resetAll() {
        paymentClient.reset();
        paymentClient.setFailuresBeforeSuccess(2);
        inventoryClient.reset();

        // Reset circuit breakers to CLOSED state
        circuitBreakerRegistry.getAllCircuitBreakers()
                .forEach(CircuitBreaker::reset);

        log.info("All simulations and circuit breakers have been reset");
        return ResponseEntity.ok(Map.of(
                "message", "All simulations reset. Payment failures set to 2. Inventory simulations disabled. Circuit breakers reset to CLOSED."
        ));
    }
}
