package com.moniewise.moniewise_backend.psp.rubies.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response from POST /{stage}/baas-wallet/create-wallet
 *
 * <p>Rubies returns a FLAT object — no nested {@code data} wrapper:
 * <pre>
 * {
 *   "customerId":      "...",
 *   "accountName":     "...",
 *   "accountNumber":   "...",
 *   "responseCode":    "00",
 *   "responseMessage": "success"
 * }
 * </pre>
 *
 * <p>The bank details are always the same for every Rubies wallet:
 * bank code {@code 090175} / bank name {@code "Rubies MFB"}.
 * They are NOT returned in the response and must be hardcoded.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RubiesCreateWalletResponse {

    @JsonProperty("responseCode")
    private String responseCode;

    @JsonProperty("responseMessage")
    private String responseMessage;

    @JsonProperty("customerId")
    private String customerId;

    @JsonProperty("accountNumber")
    private String accountNumber;

    @JsonProperty("accountName")
    private String accountName;

    // ── Convenience ───────────────────────────────────────────────────────────

    /** @return true when Rubies accepted the wallet creation (responseCode == "00") */
    public boolean isSuccess() {
        return "00".equals(responseCode);
    }

    // ── Getters & Setters ─────────────────────────────────────────────────────

    public String getResponseCode()            { return responseCode; }
    public void setResponseCode(String v)      { this.responseCode = v; }

    public String getResponseMessage()         { return responseMessage; }
    public void setResponseMessage(String v)   { this.responseMessage = v; }

    public String getCustomerId()              { return customerId; }
    public void setCustomerId(String v)        { this.customerId = v; }

    public String getAccountNumber()           { return accountNumber; }
    public void setAccountNumber(String v)     { this.accountNumber = v; }

    public String getAccountName()             { return accountName; }
    public void setAccountName(String v)       { this.accountName = v; }
}
