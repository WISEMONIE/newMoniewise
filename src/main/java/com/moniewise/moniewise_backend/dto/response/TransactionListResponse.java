// File: src/main/java/com/moniewise/moniewise_backend/dto/response/TransactionListResponse.java
package com.moniewise.moniewise_backend.dto.response;

import com.moniewise.moniewise_backend.enums.TransactionStatus;
import com.moniewise.moniewise_backend.enums.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record TransactionListResponse(
        Long id,
        String title,
        String subtitle,          // Changed from 'description' to 'subtitle' for UI clarity
        BigDecimal amount,        // Signed (+/-)
        String formattedAmount,   // e.g., "- ₦5,000.00" (Optional, if you want backend formatting)
        String iconType,          // "BANK", "USER", "WALLET", "BUDGET"
        String direction,         // "IN" or "OUT" (For coloring: Green/Red)
        String path,              // Deep link: "/transactions/105"
        LocalDateTime createdAt,
        TransactionStatus status,
        TransactionType type      // Keep enum for filters
) {}
