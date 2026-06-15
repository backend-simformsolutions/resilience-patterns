package com.simform.resilience.controller;

import com.simform.resilience.dto.OrderRequest;
import com.simform.resilience.dto.OrderResponse;
import com.simform.resilience.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for order operations.
 * <p>
 * Exposes the main business endpoint for placing an order. Internally, this delegates
 * to the {@link OrderService} which orchestrates inventory checks and payment processing,
 * both of which are protected by resilience patterns (Retry, Circuit Breaker, Timeout).
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    /**
     * Places a new order.
     * <p>
     * This endpoint triggers the full order processing pipeline:
     * <ol>
     *   <li>Input validation</li>
     *   <li>Inventory check (with Timeout + Circuit Breaker)</li>
     *   <li>Payment processing (with Retry + Circuit Breaker)</li>
     *   <li>Response aggregation</li>
     * </ol>
     * </p>
     *
     * @param request the order request payload
     * @return the order processing result
     */
    @PostMapping
    public ResponseEntity<OrderResponse> placeOrder(@RequestBody OrderRequest request) {
        log.info("Received order request: {}", request);
        OrderResponse response = orderService.processOrder(request);
        log.info("Order processed with status: {}", response.getStatus());
        return ResponseEntity.ok(response);
    }
}
