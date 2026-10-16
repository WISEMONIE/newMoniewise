package com.moniewise.moniewise_backend.dto.request;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.Future;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

public class CreateSavingsGoalRequest {

    @NotBlank(message = "Savings goal name cannot be empty")
    private String name;

    @NotNull(message = "Target amount is required")
    @DecimalMin(value = "1000.00", message = "Target amount must be at least ₦1,000")
    private BigDecimal targetAmount;

    @NotNull(message = "Initial deposit is required (can be 0)")
    @DecimalMin(value = "0.00", message = "Initial deposit cannot be negative")
    private BigDecimal initialDeposit;

    @NotNull(message = "Maturity date is required")
    @Future(message = "Maturity date must be in the future")
    private LocalDate maturityDate;

    private BigDecimal interestRate; // Optional from frontend, we can default it

    /** WEDDING, BIRTHDAY, RENT, SCHOOL, or OTHER. */
    private String category;

    private Boolean remindWeek;
    private Boolean remindDayBefore;
    private Boolean remindMorning;

    /** The create sheet must send true. The server refuses the event otherwise. */
    private Boolean termsAccepted;

    // --- Getters and Setters ---
    
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public BigDecimal getTargetAmount() { return targetAmount; }
    public void setTargetAmount(BigDecimal targetAmount) { this.targetAmount = targetAmount; }

    public BigDecimal getInitialDeposit() { return initialDeposit; }
    public void setInitialDeposit(BigDecimal initialDeposit) { this.initialDeposit = initialDeposit; }

    public LocalDate getMaturityDate() { return maturityDate; }
    public void setMaturityDate(LocalDate maturityDate) { this.maturityDate = maturityDate; }

    public BigDecimal getInterestRate() { return interestRate; }
    public void setInterestRate(BigDecimal interestRate) { this.interestRate = interestRate; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public Boolean getRemindWeek() { return remindWeek; }
    public void setRemindWeek(Boolean remindWeek) { this.remindWeek = remindWeek; }

    public Boolean getRemindDayBefore() { return remindDayBefore; }
    public void setRemindDayBefore(Boolean remindDayBefore) { this.remindDayBefore = remindDayBefore; }

    public Boolean getRemindMorning() { return remindMorning; }
    public void setRemindMorning(Boolean remindMorning) { this.remindMorning = remindMorning; }

    public Boolean getTermsAccepted() { return termsAccepted; }
    public void setTermsAccepted(Boolean termsAccepted) { this.termsAccepted = termsAccepted; }
}