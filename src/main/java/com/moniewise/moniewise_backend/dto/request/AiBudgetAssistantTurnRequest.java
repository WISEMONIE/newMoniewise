package com.moniewise.moniewise_backend.dto.request;

import com.moniewise.moniewise_backend.dto.response.AiEnvelopeSuggestion;

import java.util.List;

public class AiBudgetAssistantTurnRequest {
    private String budgetName;
    private Double totalBudget;
    private Integer durationDays;
    private String goal;
    private String currency;
    private String latestUserMessage;
    private List<AiEnvelopeSuggestion> envelopes;
    private List<AiBudgetAssistantMessage> messages;

    public String getBudgetName() {
        return budgetName;
    }

    public void setBudgetName(String budgetName) {
        this.budgetName = budgetName;
    }

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

    public String getLatestUserMessage() {
        return latestUserMessage;
    }

    public void setLatestUserMessage(String latestUserMessage) {
        this.latestUserMessage = latestUserMessage;
    }

    public List<AiEnvelopeSuggestion> getEnvelopes() {
        return envelopes;
    }

    public void setEnvelopes(List<AiEnvelopeSuggestion> envelopes) {
        this.envelopes = envelopes;
    }

    public List<AiBudgetAssistantMessage> getMessages() {
        return messages;
    }

    public void setMessages(List<AiBudgetAssistantMessage> messages) {
        this.messages = messages;
    }
}
