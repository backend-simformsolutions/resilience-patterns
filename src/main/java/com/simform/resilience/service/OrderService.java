package com.simform.resilience.service;

import com.simform.resilience.dto.*;
import com.simform.resilience.exception.BusinessValidationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Orchestrates the order processing workflow by coordinating calls to
 * the {@link PaymentService} and {@link InventoryService}.
 * <p>
 * This service does NOT apply resilience annotations directly — instead, it relies
 * on the underlying services which are already protected by Retry, Circuit Breaker,
 * and Timeout patterns. This keeps the orchestration logic clean and demonstrates
 * proper separation of concerns.
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final PaymentService paymentService;
    private final InventoryService inventoryService;

    /**
     * Processes an order end-to-end:
     * <ol>
     *   <li>Validates the incoming request.</li>
     *   <li>Checks inventory availability (protected by Timeout + Circuit Breaker).</li>
     *   <li>Processes payment (protected by Retry + Circuit Breaker).</li>
     *   <li>Builds and returns the consolidated order response.</li>
     * </ol>
     *
     * @param request the incoming order request
     * @return a complete {@link OrderResponse}
     */
    public OrderResponse processOrder(OrderRequest request) {
        log.info("Processing order — productId={}, qty={}, amount={}, customerId={}",
                request.getProductId(), request.getQuantity(), request.getAmount(), request.getCustomerId());

        // Step 1: Validate request
        validateRequest(request);

        String orderId = UUID.randomUUID().toString();

        // Step 2: Check inventory (Timeout + Circuit Breaker)
        InventoryResponse inventoryResponse = checkInventoryWithTimeout(request, orderId);

        // Step 3: Process payment (Retry + Circuit Breaker)
        PaymentResponse paymentResponse = paymentService.processPayment(
                orderId, request.getAmount(), request.getCustomerId());

        // Step 4: Build response
        String overallStatus = determineOverallStatus(paymentResponse, inventoryResponse);

        return OrderResponse.builder()
                .orderId(orderId)
                .status(overallStatus)
                .paymentStatus(paymentResponse.getStatus())
                .inventoryStatus(inventoryResponse.getStatus())
                .message(buildResponseMessage(paymentResponse, inventoryResponse))
                .timestamp(LocalDateTime.now())
                .build();
    }

    /**
     * Validates the order request for basic business rules.
     *
     * @throws BusinessValidationException if validation fails
     */
    private void validateRequest(OrderRequest request) {
        if (request.getProductId() == null || request.getProductId().isBlank()) {
            throw new BusinessValidationException("Product ID is required");
        }
        if (request.getQuantity() <= 0) {
            throw new BusinessValidationException("Quantity must be greater than zero");
        }
        if (request.getAmount() <= 0) {
            throw new BusinessValidationException("Amount must be greater than zero");
        }
        if (request.getCustomerId() == null || request.getCustomerId().isBlank()) {
            throw new BusinessValidationException("Customer ID is required");
        }
    }

    /**
     * Calls the inventory service and blocks for the result (since TimeLimiter requires
     * CompletableFuture, we join here to get the result synchronously).
     */
    private InventoryResponse checkInventoryWithTimeout(OrderRequest request, String orderId) {
        try {
            CompletableFuture<InventoryResponse> future =
                    inventoryService.checkInventory(request.getProductId(), request.getQuantity());
            return future.get();
        } catch (Exception ex) {
            log.warn("Inventory check encountered an issue for orderId={}: {}", orderId, ex.getMessage());
            return InventoryResponse.builder()
                    .productId(request.getProductId())
                    .available(false)
                    .reservedQuantity(0)
                    .status("UNKNOWN")
                    .message("Could not verify inventory: " + ex.getMessage())
                    .build();
        }
    }

    /**
     * Determines the overall order status based on payment and inventory outcomes.
     */
    private String determineOverallStatus(PaymentResponse payment, InventoryResponse inventory) {
        if ("SUCCESS".equals(payment.getStatus()) && "RESERVED".equals(inventory.getStatus())) {
            return "CONFIRMED";
        } else if ("PENDING".equals(payment.getStatus())) {
            return "PAYMENT_PENDING";
        } else if ("UNKNOWN".equals(inventory.getStatus())) {
            return "INVENTORY_CHECK_FAILED";
        }
        return "PARTIALLY_COMPLETED";
    }

    /**
     * Builds a human-readable message combining payment and inventory results.
     */
    private String buildResponseMessage(PaymentResponse payment, InventoryResponse inventory) {
        return String.format("Payment: %s | Inventory: %s", payment.getMessage(), inventory.getMessage());
    }
}
