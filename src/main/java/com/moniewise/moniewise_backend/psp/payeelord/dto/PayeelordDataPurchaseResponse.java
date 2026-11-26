package com.moniewise.moniewise_backend.psp.payeelord.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/**
 * Wire response from {@code POST /api/data} — synchronous source-of-truth for the
 * data purchase (see {@link PayeelordAirtimePurchaseResponse} for the envelope
 * rationale, which applies identically here).
 *
 * <p><strong>Real envelope shape (success):</strong>
 * <pre>{@code
 * {
 *   "status": "successful",
 *   "data": {
 *     "transaction_id": 35343276,
 *     "apiResponse": "Dear Customer, You have successfully shared 500MB ...",
 *     "network": "MTN",
 *     "balanceBefore": "35043.60",
 *     "balanceAfter": 34893.6,
 *     "mobileNumber": "08144446509",
 *     "plan_name": "500.0MB",
 *     "amount": "N150",
 *     "status": "successful",
 *     "responseTime": "0.00 secs",
 *     "date": "October 25, 2024, 12:42 PM"
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Error shape (flat, no {@code data}):</strong>
 * {@code {"status": "failed", "message": "..."}}
 *
 * <p>Note {@code transaction_id} arrives as a JSON NUMBER for data (vs a string
 * for airtime) — Jackson coerces scalar number → String by default, so a single
 * {@code String} field captures both safely.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class PayeelordDataPurchaseResponse {

    @JsonProperty("status")
    private String status;

    /** Present on error responses — e.g. "Sorry, this plan is not available at the moment." */
    @JsonProperty("message")
    private String message;

    @JsonProperty("data")
    private Data data;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Data {
        @JsonProperty("transaction_id")
        private String transactionId;

        @JsonProperty("apiResponse")
        private String apiResponse;

        @JsonProperty("network")
        private String network;

        @JsonProperty("balanceBefore")
        private BigDecimal balanceBefore;

        @JsonProperty("balanceAfter")
        private BigDecimal balanceAfter;

        @JsonProperty("mobileNumber")
        private String mobileNumber;

        @JsonProperty("plan_name")
        private String planName;

        /** Raw string as returned, e.g. "N150" — display/audit only. */
        @JsonProperty("amount")
        private String amount;

        @JsonProperty("status")
        private String status;

        @JsonProperty("responseTime")
        private String responseTime;

        @JsonProperty("date")
        private String date;

        public String getTransactionId()           { return transactionId; }
        public void setTransactionId(String v)      { this.transactionId = v; }
        public String getApiResponse()             { return apiResponse; }
        public void setApiResponse(String v)       { this.apiResponse = v; }
        public String getNetwork()                 { return network; }
        public void setNetwork(String v)           { this.network = v; }
        public BigDecimal getBalanceBefore()       { return balanceBefore; }
        public void setBalanceBefore(BigDecimal v) { this.balanceBefore = v; }
        public BigDecimal getBalanceAfter()        { return balanceAfter; }
        public void setBalanceAfter(BigDecimal v)  { this.balanceAfter = v; }
        public String getMobileNumber()            { return mobileNumber; }
        public void setMobileNumber(String v)      { this.mobileNumber = v; }
        public String getPlanName()                { return planName; }
        public void setPlanName(String v)          { this.planName = v; }
        public String getAmount()                  { return amount; }
        public void setAmount(String v)            { this.amount = v; }
        public String getStatus()                  { return status; }
        public void setStatus(String v)            { this.status = v; }
        public String getResponseTime()            { return responseTime; }
        public void setResponseTime(String v)      { this.responseTime = v; }
        public String getDate()                    { return date; }
        public void setDate(String v)              { this.date = v; }
    }

    /** Effective status: prefer top-level, fall back to nested data.status. */
    public String getStatus() {
        if (status != null && !status.isBlank()) return status;
        return data != null ? data.getStatus() : null;
    }

    public boolean isSuccessful() { return "successful".equalsIgnoreCase(getStatus()); }

    public boolean isFailed() {
        String s = getStatus();
        return s != null && !isSuccessful() && !isProcessing();
    }

    public boolean isProcessing() { return "processing".equalsIgnoreCase(getStatus()); }

    public String getTransactionId() { return data != null ? data.getTransactionId() : null; }

    public String getPlanName()      { return data != null ? data.getPlanName() : null; }

    public String getNetwork()       { return data != null ? data.getNetwork() : null; }

    public BigDecimal getBalanceBefore() { return data != null ? data.getBalanceBefore() : null; }

    public BigDecimal getBalanceAfter()  { return data != null ? data.getBalanceAfter() : null; }

    // ── Top-level getters/setters ───────────────────────────────────────────
    public void setStatus(String status)     { this.status = status; }
    public String getMessage()               { return message; }
    public void setMessage(String message)   { this.message = message; }
    public Data getData()                    { return data; }
    public void setData(Data data)           { this.data = data; }
}
