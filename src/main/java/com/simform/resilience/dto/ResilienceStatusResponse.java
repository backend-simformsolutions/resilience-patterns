package com.simform.resilience.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Provides insight into the current state of resilience components (circuit breakers, retries, etc.).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResilienceStatusResponse {

    private Map<String, String> circuitBreakerStates;
    private Map<String, Object> retryMetrics;
    private Map<String, Object> timeLimiterMetrics;
    private String message;
}
