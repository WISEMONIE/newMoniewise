package com.moniewise.moniewise_backend.dto.request;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A live event can change its name, the amount needed, its type, its reminders,
 * and its day. The day can only move later. There is no cancel on this request.
 */
public class UpdateSavingsGoalRequest {

    private String name;
    private BigDecimal targetAmount;
    private LocalDate maturityDate;
    private String category;
    private Boolean remindWeek;
    private Boolean remindDayBefore;
    private Boolean remindMorning;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public BigDecimal getTargetAmount() { return targetAmount; }
    public void setTargetAmount(BigDecimal targetAmount) { this.targetAmount = targetAmount; }

    public LocalDate getMaturityDate() { return maturityDate; }
    public void setMaturityDate(LocalDate maturityDate) { this.maturityDate = maturityDate; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public Boolean getRemindWeek() { return remindWeek; }
    public void setRemindWeek(Boolean remindWeek) { this.remindWeek = remindWeek; }

    public Boolean getRemindDayBefore() { return remindDayBefore; }
    public void setRemindDayBefore(Boolean remindDayBefore) { this.remindDayBefore = remindDayBefore; }

    public Boolean getRemindMorning() { return remindMorning; }
    public void setRemindMorning(Boolean remindMorning) { this.remindMorning = remindMorning; }
}
