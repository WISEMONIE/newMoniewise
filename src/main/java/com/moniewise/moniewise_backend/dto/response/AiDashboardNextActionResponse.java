package com.moniewise.moniewise_backend.dto.response;

import java.util.ArrayList;
import java.util.List;

public class AiDashboardNextActionResponse {
    private String title;
    private String message;
    private String ctaLabel;
    private String actionType;
    private String priority;
    private String reason;
    private String source;
    private Double confidence;
    private Long budgetId;
    private String budgetName;
    private Long envelopeId;
    private String envelopeName;
    private Double amountValue;
    private String nextAvailableAt;
    private String countdownText;
    private List<AlternativeAction> alternatives = new ArrayList<>();
    /**
     * 2–4 warm, rotating phrasings of the SAME action.
     * Gemini generates these so the card feels alive while the user
     * hasn't acted yet. Each entry carries only title + message;
     * the rest of the action metadata (actionType, budgetId, etc.)
     * is shared from the parent object.
     */
    private List<PromptVariant> variants = new ArrayList<>();

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getCtaLabel() {
        return ctaLabel;
    }

    public void setCtaLabel(String ctaLabel) {
        this.ctaLabel = ctaLabel;
    }

    public String getActionType() {
        return actionType;
    }

    public void setActionType(String actionType) {
        this.actionType = actionType;
    }

    public String getPriority() {
        return priority;
    }

    public void setPriority(String priority) {
        this.priority = priority;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public Double getConfidence() {
        return confidence;
    }

    public void setConfidence(Double confidence) {
        this.confidence = confidence;
    }

    public Long getBudgetId() {
        return budgetId;
    }

    public void setBudgetId(Long budgetId) {
        this.budgetId = budgetId;
    }

    public String getBudgetName() {
        return budgetName;
    }

    public void setBudgetName(String budgetName) {
        this.budgetName = budgetName;
    }

    public Long getEnvelopeId() {
        return envelopeId;
    }

    public void setEnvelopeId(Long envelopeId) {
        this.envelopeId = envelopeId;
    }

    public String getEnvelopeName() {
        return envelopeName;
    }

    public void setEnvelopeName(String envelopeName) {
        this.envelopeName = envelopeName;
    }

    public Double getAmountValue() {
        return amountValue;
    }

    public void setAmountValue(Double amountValue) {
        this.amountValue = amountValue;
    }

    public String getNextAvailableAt() {
        return nextAvailableAt;
    }

    public void setNextAvailableAt(String nextAvailableAt) {
        this.nextAvailableAt = nextAvailableAt;
    }

    public String getCountdownText() {
        return countdownText;
    }

    public void setCountdownText(String countdownText) {
        this.countdownText = countdownText;
    }

    public List<AlternativeAction> getAlternatives() {
        return alternatives;
    }

    public void setAlternatives(List<AlternativeAction> alternatives) {
        this.alternatives = alternatives == null ? new ArrayList<>() : alternatives;
    }

    public List<PromptVariant> getVariants() {
        return variants;
    }

    public void setVariants(List<PromptVariant> variants) {
        this.variants = variants == null ? new ArrayList<>() : variants;
    }

    /** A single warm rephrasing of the parent action. Title + message only. */
    public static class PromptVariant {
        private String title;
        private String message;

        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
    }

    public static class AlternativeAction {
        private String title;
        private String actionType;
        private String ctaLabel;
        private String reason;
        private Long budgetId;
        private String budgetName;
        private Long envelopeId;
        private String envelopeName;

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getActionType() {
            return actionType;
        }

        public void setActionType(String actionType) {
            this.actionType = actionType;
        }

        public String getCtaLabel() {
            return ctaLabel;
        }

        public void setCtaLabel(String ctaLabel) {
            this.ctaLabel = ctaLabel;
        }

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }

        public Long getBudgetId() {
            return budgetId;
        }

        public void setBudgetId(Long budgetId) {
            this.budgetId = budgetId;
        }

        public String getBudgetName() {
            return budgetName;
        }

        public void setBudgetName(String budgetName) {
            this.budgetName = budgetName;
        }

        public Long getEnvelopeId() {
            return envelopeId;
        }

        public void setEnvelopeId(Long envelopeId) {
            this.envelopeId = envelopeId;
        }

        public String getEnvelopeName() {
            return envelopeName;
        }

        public void setEnvelopeName(String envelopeName) {
            this.envelopeName = envelopeName;
        }
    }
}
