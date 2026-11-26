package com.moniewise.moniewise_backend.dto.response;

import java.util.List;

public class AiBudgetAssistantTurnResponse {
    private String assistantMessage;
    private String reasoning;
    private String source;
    private String sourceDetail;
    /**
     * What the assistant did this turn:
     * ADD_ENVELOPE    – one or more new envelopes were added
     * UPDATE_ENVELOPE – one or more existing envelopes were changed
     * REMOVE_ENVELOPE – one or more envelopes were removed
     * REBALANCE       – multiple envelopes redistributed without explicit add/remove
     * QUERY           – user asked a question; envelope list is unchanged
     * NONE            – off-topic or no actionable change
     */
    private String action;
    private Boolean readyToFinalize;
    private Double totalAllocatedPercentage;
    private Double remainingAmount;
    private List<AiEnvelopeSuggestion> envelopes;

    public String getAssistantMessage() {
        return assistantMessage;
    }

    public void setAssistantMessage(String assistantMessage) {
        this.assistantMessage = assistantMessage;
    }

    public String getReasoning() {
        return reasoning;
    }

    public void setReasoning(String reasoning) {
        this.reasoning = reasoning;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getSourceDetail() {
        return sourceDetail;
    }

    public void setSourceDetail(String sourceDetail) {
        this.sourceDetail = sourceDetail;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public Boolean getReadyToFinalize() {
        return readyToFinalize;
    }

    public void setReadyToFinalize(Boolean readyToFinalize) {
        this.readyToFinalize = readyToFinalize;
    }

    public Double getTotalAllocatedPercentage() {
        return totalAllocatedPercentage;
    }

    public void setTotalAllocatedPercentage(Double totalAllocatedPercentage) {
        this.totalAllocatedPercentage = totalAllocatedPercentage;
    }

    public Double getRemainingAmount() {
        return remainingAmount;
    }

    public void setRemainingAmount(Double remainingAmount) {
        this.remainingAmount = remainingAmount;
    }

    public List<AiEnvelopeSuggestion> getEnvelopes() {
        return envelopes;
    }

    public void setEnvelopes(List<AiEnvelopeSuggestion> envelopes) {
        this.envelopes = envelopes;
    }
}
