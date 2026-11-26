package com.moniewise.moniewise_backend.psp.payeelord.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/**
 * Wire response from {@code POST /api/buy/airtime} — the SYNCHRONOUS
 * source-of-truth for whether the purchase happened (Payeelord confirmed
 * there is no airtime webhook, only data has one).
 *
 * <p>Payeelord currently returns airtime in a FLAT shape (every field at the
 * top level). The older nested {@code data.*} envelope is kept as a fallback
 * so a future shape flip would not silently break auditability.
 *
 * <p><strong>Flat shape (current — confirmed live 2026-07-21):</strong>
 * <pre>{@code
 * {
 *   "transaction_id": 5973665,
 *   "api_response": "Congratulations! Your transaction is successful, ...",
 *   "network": "mtn",
 *   "balanceBefore": "204.10",
 *   "balanceAfter":  "194.35",
 *   "mobileNumber":  "08060214037",
 *   "paid_amount":   "N9.75",
 *   "amount":        "N10.00",
 *   "status":        "successful",
 *   "responseTime":  "2.86 secs",
 *   "date":          "July 21, 2026, 5:58 PM"
 * }
 * }</pre>
 *
 * <p><strong>Historical nested shape (kept for forward-compatibility):</strong>
 * <pre>{@code
 * { "status": "successful", "data": { "transaction_id": "...", ... } }
 * }</pre>
 *
 * <p><strong>Error shape:</strong>
 * {@code {"status": "failed", "message": "..."}}
 *
 * <p>The public getters prefer the flat/top-level fields (source of truth
 * today) and fall back to nested {@code data.*} for backward compatibility.
 * Monetary/text fields are audit/display only — the amount actually charged
 * comes from our own {@code PayeelordVasTransaction}, never from this response.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class PayeelordAirtimePurchaseResponse {

    // ── Top-level fields (flat shape — current live format) ──────────────────
    @JsonProperty("status")
    private String status;

    /** Present on error responses — e.g. "Insufficient wallet balance." */
    @JsonProperty("message")
    private String message;

    // Jackson coerces a JSON number → String automatically here, so a numeric
    // transaction_id (e.g. 5973665) is safely captured as a String.
    @JsonProperty("transaction_id")
    private String transactionId;

    // Payeelord live uses "api_response" (snake_case); the older nested docs
    // showed "apiResponse". Accept both spellings so a future flip either
    // direction can't silently drop this field.
    @JsonAlias({"api_response", "apiResponse"})
    private String apiResponse;

    @JsonProperty("network")
    private String network;

    @JsonProperty("balanceBefore")
    private BigDecimal balanceBefore;

    @JsonProperty("balanceAfter")
    private BigDecimal balanceAfter;

    @JsonProperty("mobileNumber")
    private String mobileNumber;

    /** Raw string as returned, e.g. "N9.75" — display/audit only. */
    @JsonProperty("paid_amount")
    private String paidAmount;

    /** Raw string as returned, e.g. "N10.00" — display/audit only. */
    @JsonProperty("amount")
    private String amount;

    @JsonProperty("responseTime")
    private String responseTime;

    @JsonProperty("date")
    private String date;

    // ── Historical nested envelope (forward-compatibility only) ─────────────
    @JsonProperty("data")
    private Data data;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Data {
        @JsonProperty("transaction_id")
        private String transactionId;

        // Same snake/camel forgiveness as the top-level field.
        @JsonAlias({"api_response", "apiResponse"})
        private String apiResponse;

        @JsonProperty("network")
        private String network;

        @JsonProperty("balanceBefore")
        private BigDecimal balanceBefore;

        @JsonProperty("balanceAfter")
        private BigDecimal balanceAfter;

        @JsonProperty("mobileNumber")
        private String mobileNumber;

        @JsonProperty("paid_amount")
        private String paidAmount;

        @JsonProperty("amount")
        private String amount;

        @JsonProperty("status")
        private String status;

        @JsonProperty("responseTime")
        private String responseTime;

        @JsonProperty("date")
        private String date;

        public String getTransactionId()           { return transactionId; }
        public void setTransactionId(String v)     { this.transactionId = v; }
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
        public String getPaidAmount()              { return paidAmount; }
        public void setPaidAmount(String v)        { this.paidAmount = v; }
        public String getAmount()                  { return amount; }
        public void setAmount(String v)            { this.amount = v; }
        public String getStatus()                  { return status; }
        public void setStatus(String v)            { this.status = v; }
        public String getResponseTime()            { return responseTime; }
        public void setResponseTime(String v)      { this.responseTime = v; }
        public String getDate()                    { return date; }
        public void setDate(String v)              { this.date = v; }
    }

    // ── Public dispatchers: prefer flat, fall back to nested ────────────────

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

    /** Provider transaction id — the audit anchor that must always land in the DB. */
    public String getTransactionId() {
        if (transactionId != null && !transactionId.isBlank()) return transactionId;
        return data != null ? data.getTransactionId() : null;
    }

    public String getNetwork() {
        if (network != null && !network.isBlank()) return network;
        return data != null ? data.getNetwork() : null;
    }

    public BigDecimal getBalanceBefore() {
        if (balanceBefore != null) return balanceBefore;
        return data != null ? data.getBalanceBefore() : null;
    }

    public BigDecimal getBalanceAfter() {
        if (balanceAfter != null) return balanceAfter;
        return data != null ? data.getBalanceAfter() : null;
    }

    public String getApiResponse() {
        if (apiResponse != null && !apiResponse.isBlank()) return apiResponse;
        return data != null ? data.getApiResponse() : null;
    }

    // ── Top-level getters/setters ───────────────────────────────────────────
    public void setStatus(String status)         { this.status = status; }
    public String getMessage()                   { return message; }
    public void setMessage(String message)       { this.message = message; }
    public Data getData()                        { return data; }
    public void setData(Data data)               { this.data = data; }

    public void setTransactionId(String v)       { this.transactionId = v; }
    public void setApiResponse(String v)         { this.apiResponse = v; }
    public void setNetwork(String v)             { this.network = v; }
    public void setBalanceBefore(BigDecimal v)   { this.balanceBefore = v; }
    public void setBalanceAfter(BigDecimal v)    { this.balanceAfter = v; }
    public String getMobileNumber()              { return mobileNumber; }
    public void setMobileNumber(String v)        { this.mobileNumber = v; }
    public String getPaidAmount()                { return paidAmount; }
    public void setPaidAmount(String v)          { this.paidAmount = v; }
    public String getAmount()                    { return amount; }
    public void setAmount(String v)              { this.amount = v; }
    public String getResponseTime()              { return responseTime; }
    public void setResponseTime(String v)        { this.responseTime = v; }
    public String getDate()                      { return date; }
    public void setDate(String v)                { this.date = v; }
}
