package com.moniewise.moniewise_backend.dto.request;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CreateFromTemplateRequest {
    private String name;
    private BigDecimal totalAmount;
    private Integer durationDays;
    private LocalDate startDate;
    private LocalDate endDate;
}
