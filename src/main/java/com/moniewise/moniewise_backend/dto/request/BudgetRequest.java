package com.moniewise.moniewise_backend.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.moniewise.moniewise_backend.enums.BudgetStatus;
import com.sun.istack.NotNull;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import javax.validation.constraints.Future;
import javax.validation.constraints.FutureOrPresent;
import javax.validation.constraints.NotEmpty;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class BudgetRequest {
    private String name;

    private BigDecimal totalAmount; // Changed to BigDecimal
    private Integer durationDays;
    private LocalDate startDate;
    private LocalDate endDate;
    private BudgetStatus status; // String for input, converted to BudgetStatus

    @NotEmpty
    private List<EnvelopeRequest> envelopes;

    private Boolean termsAccepted;
}