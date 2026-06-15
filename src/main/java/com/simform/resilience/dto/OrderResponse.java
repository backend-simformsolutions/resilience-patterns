package com.simform.resilience.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Represents the response returned after order processing.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderResponse {

    private String orderId;
    private String status;
    private String paymentStatus;
    private String inventoryStatus;
    private String message;
    private LocalDateTime timestamp;
}
