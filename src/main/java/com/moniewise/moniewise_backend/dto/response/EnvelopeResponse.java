//package com.moniewise.moniewise_backend.dto.response;
//
//import java.math.BigDecimal;
//import java.time.LocalDateTime;
//import java.util.Map;
//
//import lombok.Getter;
//import lombok.Setter;
//import lombok.NoArgsConstructor;
//import lombok.AllArgsConstructor;
//
//@Getter
//@Setter
//@NoArgsConstructor
//@AllArgsConstructor
//public class EnvelopeResponse {
//    private Long id;
//    private Long budgetId;
//    private String name;
//    private BigDecimal amount;
//    private BigDecimal remainingAmount;
//    private Map<String, Object> conditions;
//    private LocalDateTime createdAt;
//}

package com.moniewise.moniewise_backend.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class EnvelopeResponse {
    private Long id;
    private Long budgetId;
    private String name;


    private BigDecimal amount;
    private BigDecimal remainingAmount;

    private BigDecimal initialAmount;
    private BigDecimal totalRemaining;
    private BigDecimal periodRemaining;
    private BigDecimal periodLimit;
    private BigDecimal usedThisPeriod;

    private BigDecimal heldAmount;

    private boolean isAutomated;

    private Map<String, Object> conditions;
    private LocalDateTime createdAt;
    private LocalDateTime lastDisbursedAt;
    private LocalDateTime nextDisbursementAt;

}