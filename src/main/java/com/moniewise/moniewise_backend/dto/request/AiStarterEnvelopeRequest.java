package com.moniewise.moniewise_backend.dto.request;

public class AiStarterEnvelopeRequest {
    private Double totalBudget;
    private Integer durationDays;
    private String goal;
    private String currency;
    /**
     * When true the AI must INTERPRET the user's own plan (written in free text
     * inside {@code goal}) instead of suggesting a new one.
     * Set by the "Write it out" flow on the frontend.
     */
    private boolean interpretUserPlan = false;

    public Double getTotalBudget() {
        return totalBudget;
    }

    public void setTotalBudget(Double totalBudget) {
        this.totalBudget = totalBudget;
    }

    public Integer getDurationDays() {
        return durationDays;
    }

    public void setDurationDays(Integer durationDays) {
        this.durationDays = durationDays;
    }

    public String getGoal() {
        return goal;
    }

    public void setGoal(String goal) {
        this.goal = goal;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public boolean isInterpretUserPlan() {
        return interpretUserPlan;
    }

    public void setInterpretUserPlan(boolean interpretUserPlan) {
        this.interpretUserPlan = interpretUserPlan;
    }
}

