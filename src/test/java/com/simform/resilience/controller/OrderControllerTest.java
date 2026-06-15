package com.simform.resilience.controller;

import com.simform.resilience.client.ExternalInventoryClient;
import com.simform.resilience.client.ExternalPaymentClient;
import com.simform.resilience.dto.OrderRequest;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for the {@link OrderController} that exercise the full
 * order processing pipeline through HTTP requests.
 * <p>
 * These tests verify end-to-end behavior including how resilience patterns
 * affect the final HTTP response returned to the client.
 * </p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ExternalPaymentClient paymentClient;

    @Autowired
    private ExternalInventoryClient inventoryClient;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @BeforeEach
    void setUp() {
        paymentClient.reset();
        paymentClient.setFailuresBeforeSuccess(2);
        inventoryClient.reset();
        circuitBreakerRegistry.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
    }

    @Test
    @DisplayName("POST /api/v1/orders — Should process order successfully with retries")
    void shouldProcessOrderSuccessfully() throws Exception {
        OrderRequest request = OrderRequest.builder()
                .productId("PROD-001")
                .quantity(2)
                .amount(149.99)
                .customerId("CUST-001")
                .build();

        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").isNotEmpty())
                .andExpect(jsonPath("$.paymentStatus").value("SUCCESS"))
                .andExpect(jsonPath("$.inventoryStatus").value("RESERVED"));
    }

    @Test
    @DisplayName("POST /api/v1/orders — Should return 400 for invalid request")
    void shouldRejectInvalidRequest() throws Exception {
        OrderRequest request = OrderRequest.builder()
                .productId("")  // Invalid: blank product ID
                .quantity(0)    // Invalid: zero quantity
                .amount(100.0)
                .customerId("CUST-001")
                .build();

        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("POST /api/v1/orders — Should return fallback response when payment service is down")
    void shouldReturnFallbackWhenPaymentFails() throws Exception {
        // Configure payment to always fail
        paymentClient.setFailuresBeforeSuccess(100);

        OrderRequest request = OrderRequest.builder()
                .productId("PROD-002")
                .quantity(1)
                .amount(50.0)
                .customerId("CUST-002")
                .build();

        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentStatus").value("PENDING"));
    }

    @Test
    @DisplayName("POST /api/v1/orders — Should handle inventory timeout gracefully")
    void shouldHandleInventoryTimeout() throws Exception {
        // Configure inventory to be slow (exceeds the 2s timeout)
        inventoryClient.setSimulateDelay(true);
        inventoryClient.setDelayMillis(5000);

        OrderRequest request = OrderRequest.builder()
                .productId("PROD-003")
                .quantity(3)
                .amount(75.0)
                .customerId("CUST-003")
                .build();

        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inventoryStatus").isNotEmpty());
    }
}
