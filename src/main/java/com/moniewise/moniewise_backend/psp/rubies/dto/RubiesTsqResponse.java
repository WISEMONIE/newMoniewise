package com.moniewise.moniewise_backend.psp.rubies.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Set;

/**
 * Response from POST /{stage}/baas-transaction/tsq (Transaction Status Query)
 *
 * <p><strong>Shape note:</strong> the official {@code TsqResponse} schema is
 * completely FLAT — {@code transactionStatus}, {@code paymentReference},
 * {@code sessionId}, etc. all sit at the top level. There is NO nested
 * {@code data} wrapper (an earlier dev-sandbox assumption that doesn't match
 * the production schema).
 *
 * <p>Status logic in the gateway relies solely on {@link #isSuccess()} /
 * {@link #isPending()} / {@link #isFailed()}, which are driven by the
 * top-level {@code responseCode} — so flattening this DTO carries zero
 * functional risk to existing call sites (nothing ever called
 * {@code getData()} on this class).
 *
 * <p>Response codes:
 * <ul>
 *   <li>{@code "00"} — success / settled</li>
 *   <li>{@code "09"}, {@code "90"}, {@code "99"} — pending (keep polling)</li>
 *   <li>anything else — failed</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RubiesTsqResponse {

    private static final Set<String> PENDING_CODES = Set.of("09", "90", "99");

    @JsonProperty("responseCode")
    private String responseCode;

    @JsonProperty("responseMessage")
    private String responseMessage;

    /** Renamed from the old nested "status" — the official flat field is "transactionStatus". */
    @JsonProperty("transactionStatus")
    private String transactionStatus;

    /** Renamed from the old nested "transactionReference" — the official flat field is "paymentReference". */
    @JsonProperty("paymentReference")
    private String paymentReference;

    @JsonProperty("sessionId")
    private String sessionId;

    @JsonProperty("amount")
    private String amount;

    @JsonProperty("narration")
    private String narration;

    @JsonProperty("contractReference")
    private String contractReference;

    @JsonProperty("bankCode")
    private String bankCode;

    @JsonProperty("bankName")
    private String bankName;

    @JsonProperty("creditAccountNumber")
    private String creditAccountNumber;

    @JsonProperty("creditAccountName")
    private String creditAccountName;

    @JsonProperty("debitAccountName")
    private String debitAccountName;

    @JsonProperty("debitAccountNumber")
    private String debitAccountNumber;

    public boolean isSuccess() {
        return "00".equals(responseCode);
    }

    public boolean isPending() {
        return PENDING_CODES.contains(responseCode);
    }

    public boolean isFailed() {
        return !isSuccess() && !isPending();
    }

    // ── Getters & Setters ─────────────────────────────────────────────────────

    public String getResponseCode()                 { return responseCode; }
    public void setResponseCode(String v)           { this.responseCode = v; }

    public String getResponseMessage()              { return responseMessage; }
    public void setResponseMessage(String v)        { this.responseMessage = v; }

    public String getTransactionStatus()            { return transactionStatus; }
    public void setTransactionStatus(String v)      { this.transactionStatus = v; }

    public String getPaymentReference()             { return paymentReference; }
    public void setPaymentReference(String v)       { this.paymentReference = v; }

    public String getSessionId()                    { return sessionId; }
    public void setSessionId(String v)              { this.sessionId = v; }

    public String getAmount()                       { return amount; }
    public void setAmount(String v)                 { this.amount = v; }

    public String getNarration()                    { return narration; }
    public void setNarration(String v)              { this.narration = v; }

    public String getContractReference()            { return contractReference; }
    public void setContractReference(String v)      { this.contractReference = v; }

    public String getBankCode()                     { return bankCode; }
    public void setBankCode(String v)               { this.bankCode = v; }

    public String getBankName()                     { return bankName; }
    public void setBankName(String v)               { this.bankName = v; }

    public String getCreditAccountNumber()          { return creditAccountNumber; }
    public void setCreditAccountNumber(String v)    { this.creditAccountNumber = v; }

    public String getCreditAccountName()            { return creditAccountName; }
    public void setCreditAccountName(String v)      { this.creditAccountName = v; }

    public String getDebitAccountName()             { return debitAccountName; }
    public void setDebitAccountName(String v)       { this.debitAccountName = v; }

    public String getDebitAccountNumber()           { return debitAccountNumber; }
    public void setDebitAccountNumber(String v)     { this.debitAccountNumber = v; }
}
