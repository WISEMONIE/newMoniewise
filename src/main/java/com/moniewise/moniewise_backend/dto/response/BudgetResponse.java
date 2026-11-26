package com.moniewise.moniewise_backend.dto.response;//package com.moniewise.moniewise_backend.dto.response;
//
//import java.math.BigDecimal;
//import java.time.LocalDate;
//import java.time.LocalDateTime;
//import java.util.ArrayList;
//import java.util.List;
//
//import com.moniewise.moniewise_backend.enums.BudgetStatus;
//import lombok.Getter;
//import lombok.Setter;
//import lombok.NoArgsConstructor;
//import lombok.AllArgsConstructor;
//
//@Getter
//@Setter
//@NoArgsConstructor
//@AllArgsConstructor
//public class BudgetResponse {
//    private Long id;
//    private String name;
//    private BigDecimal totalAmount;
//    private BigDecimal allocatedAmount;
//    private Integer durationDays;
//    private LocalDate startDate;
//    private LocalDate endDate;
//    private BudgetStatus status;
//    private LocalDateTime createdAt;
//    private Long userId; // Changed from entity to ID
//    private LocalDateTime lastTopupTime;
//    private List<EnvelopeResponse> envelopes; // Added for future use
//
//    // Constructor matching mapToResponse
//    public BudgetResponse(Long id, String name, BigDecimal totalAmount, BigDecimal allocatedAmount, Integer durationDays,
//                          LocalDate startDate, LocalDate endDate, BudgetStatus status, LocalDateTime createdAt,
//                          Long userId, LocalDateTime lastTopupTime) {
//        this.id = id;
//        this.name = name;
//        this.totalAmount = totalAmount;
//        this.allocatedAmount = allocatedAmount;
//        this.durationDays = durationDays;
//        this.startDate = startDate;
//        this.endDate = endDate;
//        this.status = status;
//        this.createdAt = createdAt;
//        this.userId = userId;
//        this.lastTopupTime = lastTopupTime;
//        this.envelopes = new ArrayList<>(); // Default empty, filled later
//    }
//
//    // Getters and Setters
//    public Long getId() { return id; }
//    public void setId(Long id) { this.id = id; }
//    public String getName() { return name; }
//    public void setName(String name) { this.name = name; }
//    public BigDecimal getTotalAmount() { return totalAmount; }
//    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }
//    public BigDecimal getAllocatedAmount() { return allocatedAmount; }
//    public void setAllocatedAmount(BigDecimal allocatedAmount) { this.allocatedAmount = allocatedAmount; }
//    public Integer getDurationDays() { return durationDays; }
//    public void setDurationDays(Integer durationDays) { this.durationDays = durationDays; }
//    public LocalDate getStartDate() { return startDate; }
//    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }
//    public LocalDate getEndDate() { return endDate; }
//    public void setEndDate(LocalDate endDate) { this.endDate = endDate; }
//    public BudgetStatus getStatus() { return status; }
//    public void setStatus(BudgetStatus status) { this.status = status; }
//    public LocalDateTime getCreatedAt() { return createdAt; }
//    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
//    public Long getUserId() { return userId; }
//    public void setUserId(Long userId) { this.userId = userId; }
//    public LocalDateTime getLastTopupTime() { return lastTopupTime; }
//    public void setLastTopupTime(LocalDateTime lastTopupTime) { this.lastTopupTime = lastTopupTime; }
//    public List<EnvelopeResponse> getEnvelopes() { return envelopes; }
//    public void setEnvelopes(List<EnvelopeResponse> envelopes) { this.envelopes = envelopes; }
//}


//package com.moniewise.moniewise_backend.dto.response;
//
//import java.math.BigDecimal;
//import java.time.LocalDate;
//import java.time.LocalDateTime;
//import java.util.ArrayList;
//import java.util.List;
//
//import com.moniewise.moniewise_backend.enums.BudgetStatus;
//import lombok.Getter;
//import lombok.Setter;
//import lombok.NoArgsConstructor;
//import lombok.AllArgsConstructor;
//
//@Getter
//@Setter
//@NoArgsConstructor
//@AllArgsConstructor
//public class BudgetResponse {
//    private Long id;
//    private String name;
//    private BigDecimal totalAmount;
//    private BigDecimal allocatedAmount;
//    private Integer durationDays;
//    private LocalDate startDate;
//    private LocalDate endDate;
//    private BudgetStatus status;
//    private LocalDateTime createdAt;
//    private Long userId;
//    private LocalDateTime lastTopupTime;
//    private List<EnvelopeResponse> envelopes;
//    private BigDecimal originalAmount; // New field
//    private BigDecimal feeAmount; // New field
//
//    // Constructor matching mapToResponse (11 args, no envelopes)
//    public BudgetResponse(Long id, String name, BigDecimal totalAmount, BigDecimal allocatedAmount,
//                          Integer durationDays, LocalDate startDate, LocalDate endDate,
//                          BudgetStatus status, LocalDateTime createdAt, Long userId,
//                          LocalDateTime lastTopupTime) {
//        this.id = id;
//        this.name = name;
//        this.totalAmount = totalAmount;
//        this.allocatedAmount = allocatedAmount;
//        this.durationDays = durationDays;
//        this.startDate = startDate;
//        this.endDate = endDate;
//        this.status = status;
//        this.createdAt = createdAt;
//        this.userId = userId;
//        this.lastTopupTime = lastTopupTime;
//        this.envelopes = new ArrayList<>();
//    }
//
//    // New constructor for createBudget (14 args)
//    public BudgetResponse(Long id, String name, BigDecimal totalAmount, BigDecimal allocatedAmount,
//                          Integer durationDays, LocalDate startDate, LocalDate endDate,
//                          BudgetStatus status, LocalDateTime createdAt, Long userId,
//                          LocalDateTime lastTopupTime, List<EnvelopeResponse> envelopes,
//                          BigDecimal originalAmount, BigDecimal feeAmount) {
//        this.id = id;
//        this.name = name;
//        this.totalAmount = totalAmount;
//        this.allocatedAmount = allocatedAmount;
//        this.durationDays = durationDays;
//        this.startDate = startDate;
//        this.endDate = endDate;
//        this.status = status;
//        this.createdAt = createdAt;
//        this.userId = userId;
//        this.lastTopupTime = lastTopupTime;
//        this.envelopes = envelopes;
//        this.originalAmount = originalAmount;
//        this.feeAmount = feeAmount;
//    }
//
//    // Getters and Setters
//    public Long getId() { return id; }
//    public void setId(Long id) { this.id = id; }
//    public String getName() { return name; }
//    public void setName(String name) { this.name = name; }
//    public BigDecimal getTotalAmount() { return totalAmount; }
//    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }
//    public BigDecimal getAllocatedAmount() { return allocatedAmount; }
//    public void setAllocatedAmount(BigDecimal allocatedAmount) { this.allocatedAmount = allocatedAmount; }
//    public Integer getDurationDays() { return durationDays; }
//    public void setDurationDays(Integer durationDays) { this.durationDays = durationDays; }
//    public LocalDate getStartDate() { return startDate; }
//    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }
//    public LocalDate getEndDate() { return endDate; }
//    public void setEndDate(LocalDate endDate) { this.endDate = endDate; }
//    public BudgetStatus getStatus() { return status; }
//    public void setStatus(BudgetStatus status) { this.status = status; }
//    public LocalDateTime getCreatedAt() { return createdAt; }
//    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
//    public Long getUserId() { return userId; }
//    public void setUserId(Long userId) { this.userId = userId; }
//    public LocalDateTime getLastTopupTime() { return lastTopupTime; }
//    public void setLastTopupTime(LocalDateTime lastTopupTime) { this.lastTopupTime = lastTopupTime; }
//    public List<EnvelopeResponse> getEnvelopes() { return envelopes; }
//    public void setEnvelopes(List<EnvelopeResponse> envelopes) { this.envelopes = envelopes; }
//    public BigDecimal getOriginalAmount() { return originalAmount; }
//    public void setOriginalAmount(BigDecimal originalAmount) { this.originalAmount = originalAmount; }
//    public BigDecimal getFeeAmount() { return feeAmount; }
//    public void setFeeAmount(BigDecimal feeAmount) { this.feeAmount = feeAmount; }
//}

//package com.moniewise.moniewise_backend.dto.response;
//
//import java.math.BigDecimal;
//import java.time.LocalDate;
//import java.time.LocalDateTime;
//import java.util.ArrayList;
//import java.util.List;
//import com.moniewise.moniewise_backend.enums.BudgetStatus;
//import lombok.Getter;
//import lombok.Setter;
//import lombok.NoArgsConstructor;
//import lombok.AllArgsConstructor;

//@Getter
//@Setter
//@NoArgsConstructor
//@AllArgsConstructor
//public class BudgetResponse {
//    private Long id;
//    private String name;
//    private BigDecimal totalAmount;
//    private BigDecimal allocatedAmount;
//    private BigDecimal remaining; // New for getDashboard
//    private Integer durationDays;
//    private LocalDate startDate;
//    private LocalDate endDate;
//    private BudgetStatus status;
//    private LocalDateTime createdAt;
//    private Long userId;
//    private LocalDateTime lastTopupTime;
//    private List<EnvelopeResponse> envelopes;
//    private BigDecimal originalAmount; // For createBudget
//    private BigDecimal feeAmount; // For createBudget
//
//    // 11-arg constructor (no envelopes)
//    public BudgetResponse(Long id, String name, BigDecimal totalAmount, BigDecimal allocatedAmount,
//                          Integer durationDays, LocalDate startDate, LocalDate endDate,
//                          BudgetStatus status, LocalDateTime createdAt, Long userId,
//                          LocalDateTime lastTopupTime) {
//        this.id = id;
//        this.name = name;
//        this.totalAmount = totalAmount;
//        this.allocatedAmount = allocatedAmount;
//        this.durationDays = durationDays;
//        this.startDate = startDate;
//        this.endDate = endDate;
//        this.status = status;
//        this.createdAt = createdAt;
//        this.userId = userId;
//        this.lastTopupTime = lastTopupTime;
//        this.envelopes = new ArrayList<>();
//    }
//
//    // 13-arg constructor for getDashboard
//    public BudgetResponse(Long id, String name, BigDecimal totalAmount, BigDecimal allocatedAmount,
//                          BigDecimal remaining, Integer durationDays, LocalDate startDate,
//                          LocalDate endDate, BudgetStatus status, LocalDateTime createdAt,
//                          Long userId, LocalDateTime lastTopupTime, List<EnvelopeResponse> envelopes) {
//        this.id = id;
//        this.name = name;
//        this.totalAmount = totalAmount;
//        this.allocatedAmount = allocatedAmount;
//        this.remaining = remaining;
//        this.durationDays = durationDays;
//        this.startDate = startDate;
//        this.endDate = endDate;
//        this.status = status;
//        this.createdAt = createdAt;
//        this.userId = userId;
//        this.lastTopupTime = lastTopupTime;
//        this.envelopes = envelopes;
//    }
//
//    // 14-arg constructor for createBudget
//    public BudgetResponse(Long id, String name, BigDecimal totalAmount, BigDecimal allocatedAmount,
//                          Integer durationDays, LocalDate startDate, LocalDate endDate,
//                          BudgetStatus status, LocalDateTime createdAt, Long userId,
//                          LocalDateTime lastTopupTime, List<EnvelopeResponse> envelopes,
//                          BigDecimal originalAmount, BigDecimal feeAmount) {
//        this.id = id;
//        this.name = name;
//        this.totalAmount = totalAmount;
//        this.allocatedAmount = allocatedAmount;
//        this.durationDays = durationDays;
//        this.startDate = startDate;
//        this.endDate = endDate;
//        this.status = status;
//        this.createdAt = createdAt;
//        this.userId = userId;
//        this.lastTopupTime = lastTopupTime;
//        this.envelopes = envelopes;
//        this.originalAmount = originalAmount;
//        this.feeAmount = feeAmount;
//    }
//
//    // Getters and Setters
//    public BigDecimal getRemaining() { return remaining; }
//    public void setRemaining(BigDecimal remaining) { this.remaining = remaining; }
//    // ... other getters/setters unchanged ...
//}


// NEW AND ACTIVELY WORKING CODE

import com.moniewise.moniewise_backend.enums.BudgetStatus;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class BudgetResponse {
    private Long id;
    private String name;
    private BigDecimal totalAmount;
    private BigDecimal allocatedAmount;
    private BigDecimal remaining; // For getDashboard
    private Integer durationDays;
    private LocalDate startDate;
    private LocalDate endDate;
    private BudgetStatus status;
    private LocalDateTime createdAt;
    private Long userId;
    private LocalDateTime lastTopupTime;
    private List<EnvelopeResponse> envelopes;
    private BigDecimal originalAmount; // For createBudget
    private BigDecimal feeAmount; // For createBudget

    // 11-arg constructor (no envelopes)
    public BudgetResponse(Long id, String name, BigDecimal totalAmount, BigDecimal allocatedAmount,
                          Integer durationDays, LocalDate startDate, LocalDate endDate,
                          BudgetStatus status, LocalDateTime createdAt, Long userId,
                          LocalDateTime lastTopupTime) {
        this.id = id;
        this.name = name;
        this.totalAmount = totalAmount;
        this.allocatedAmount = allocatedAmount;
        this.durationDays = durationDays;
        this.startDate = startDate;
        this.endDate = endDate;
        this.status = status;
        this.createdAt = createdAt;
        this.userId = userId;
        this.lastTopupTime = lastTopupTime;
        this.envelopes = new ArrayList<>();
    }

    // 13-arg constructor for getDashboard
    public BudgetResponse(Long id, String name, BigDecimal totalAmount, BigDecimal allocatedAmount,
                          BigDecimal remaining, Integer durationDays, LocalDate startDate,
                          LocalDate endDate, BudgetStatus status, LocalDateTime createdAt,
                          Long userId, LocalDateTime lastTopupTime, List<EnvelopeResponse> envelopes) {
        this.id = id;
        this.name = name;
        this.totalAmount = totalAmount;
        this.allocatedAmount = allocatedAmount;
        this.remaining = remaining;
        this.durationDays = durationDays;
        this.startDate = startDate;
        this.endDate = endDate;
        this.status = status;
        this.createdAt = createdAt;
        this.userId = userId;
        this.lastTopupTime = lastTopupTime;
        this.envelopes = envelopes;
    }

    // 14-arg constructor for createBudget
    public BudgetResponse(Long id, String name, BigDecimal totalAmount, BigDecimal allocatedAmount,
                          Integer durationDays, LocalDate startDate, LocalDate endDate,
                          BudgetStatus status, LocalDateTime createdAt, Long userId,
                          LocalDateTime lastTopupTime, List<EnvelopeResponse> envelopes,
                          BigDecimal originalAmount, BigDecimal feeAmount) {
        this.id = id;
        this.name = name;
        this.totalAmount = totalAmount;
        this.allocatedAmount = allocatedAmount;
        this.durationDays = durationDays;
        this.startDate = startDate;
        this.endDate = endDate;
        this.status = status;
        this.createdAt = createdAt;
        this.userId = userId;
        this.lastTopupTime = lastTopupTime;
        this.envelopes = envelopes;
        this.originalAmount = originalAmount;
        this.feeAmount = feeAmount;
    }
}