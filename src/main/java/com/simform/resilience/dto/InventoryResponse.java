package com.simform.resilience.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents the response from the Inventory Service.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InventoryResponse {

    private String productId;
    private boolean available;
    private int reservedQuantity;
    private String status;
    private String message;
}
