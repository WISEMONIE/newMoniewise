package com.moniewise.moniewise_backend.psp.rubies.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Set;

/**
 * Response from POST /{stage}/baas-transaction/fund-transfer
 *
 * <p><strong>Shape note:</strong> the official {@code FundTransferResponse} schema
 * is completely FLAT — {@code sessionId}, {@code reference}, {@code contractReference},
 * {@code amount}, etc. all sit at the top level. There is NO nested {@code data}
 * wrapper (an earlier dev-sandbox assumption that doesn't match the production
 * schema — it would have left {@code sessionId}/{@code contractReference} from a
 * live transfer uncaptured, silently falling back to our own local reference).
 *
 * <p>Response codes:
 * <ul>
 *   <li>{@code "00"} — success / settled</li>
 *   <li>{@code "09"}, {@code "90"}, {@code "99"} — pending (poll TSQ)</li>
 *   <li>anything else — failed</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RubiesFundTransferResponse {

    private static final Set<String> PENDING_CODES = Set.of("09", "90", "99");

    @JsonProperty("responseCode")
    private String responseCode;

    @JsonProperty("responseMessage")
    private String responseMessage;

    @JsonProperty("sessionId")
    private String sessionId;

    /** Echoes back the reference we sent (or one Rubies generated if ours was absent). */
    @JsonProperty("reference")
    private String reference;

    @JsonProperty("contractReference")
    private String contractReference;

    @JsonProperty("amount")
    private String amount;

    @JsonProperty("creditAccount")
    private String creditAccount;

    @JsonProperty("creditAccountName")
    private String creditAccountName;

    @JsonProperty("debitAccountNumber")
    private String debitAccountNumber;

    @JsonProperty("narration")
    private String narration;

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

    public String getResponseCode()                { return responseCode; }
    public void setResponseCode(String v)          { this.responseCode = v; }

    public String getResponseMessage()             { return responseMessage; }
    public void setResponseMessage(String v)       { this.responseMessage = v; }

    public String getSessionId()                   { return sessionId; }
    public void setSessionId(String v)             { this.sessionId = v; }

    public String getReference()                   { return reference; }
    public void setReference(String v)             { this.reference = v; }

    public String getContractReference()           { return contractReference; }
    public void setContractReference(String v)     { this.contractReference = v; }

    public String getAmount()                      { return amount; }
    public void setAmount(String v)                { this.amount = v; }

    public String getCreditAccount()               { return creditAccount; }
    public void setCreditAccount(String v)         { this.creditAccount = v; }

    public String getCreditAccountName()           { return creditAccountName; }
    public void setCreditAccountName(String v)     { this.creditAccountName = v; }

    public String getDebitAccountNumber()          { return debitAccountNumber; }
    public void setDebitAccountNumber(String v)    { this.debitAccountNumber = v; }

    public String getNarration()                   { return narration; }
    public void setNarration(String v)             { this.narration = v; }
}
