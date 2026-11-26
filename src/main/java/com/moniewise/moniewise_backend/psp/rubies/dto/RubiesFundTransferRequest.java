package com.moniewise.moniewise_backend.psp.rubies.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request body for POST /{stage}/baas-transaction/fund-transfer
 *
 * <p><strong>Critical:</strong> {@code amount} must be a STRING, not a number.
 * Rubies rejects numeric amounts with a 400 / validation error.
 *
 * <p><strong>Field name note:</strong> per the official Rubies {@code FundTransferRequest}
 * schema, our idempotency/tracking string must be sent as {@code reference} —
 * NOT {@code transactionReference} (an earlier dev-sandbox assumption that the
 * production schema does not recognise). Sending the wrong key means Rubies
 * silently ignores it and auto-generates its own reference, which then breaks
 * webhook/TSQ reconciliation against our local records.
 */
public class RubiesFundTransferRequest {

    @JsonProperty("debitAccountNumber")
    private String debitAccountNumber;

    @JsonProperty("debitAccountName")
    private String debitAccountName;

    @JsonProperty("bankCode")
    private String creditBankCode;

    @JsonProperty("bankName")
    private String creditBankName;

    @JsonProperty("creditAccountNumber")
    private String creditAccountNumber;

    @JsonProperty("creditAccountName")
    private String creditAccountName;

    /** Must be a plain numeric string — e.g. "5000.00" */
    @JsonProperty("amount")
    private String amount;

    /** Our unique tracking string — wire key MUST be "reference" (see class Javadoc). */
    @JsonProperty("reference")
    private String reference;

    @JsonProperty("narration")
    private String narration;

    public RubiesFundTransferRequest() {}

    public RubiesFundTransferRequest(String debitAccountNumber, String debitAccountName,
                                     String creditBankCode, String creditBankName,
                                     String creditAccountNumber, String creditAccountName,
                                     String amount, String reference, String narration) {
        this.debitAccountNumber  = debitAccountNumber;
        this.debitAccountName    = debitAccountName;
        this.creditBankCode      = creditBankCode;
        this.creditBankName      = creditBankName;
        this.creditAccountNumber = creditAccountNumber;
        this.creditAccountName   = creditAccountName;
        this.amount              = amount;
        this.reference           = reference;
        this.narration           = narration;
    }

    // ── Getters & Setters ─────────────────────────────────────────────────────

    public String getDebitAccountNumber()             { return debitAccountNumber; }
    public void setDebitAccountNumber(String v)       { this.debitAccountNumber = v; }

    public String getDebitAccountName()               { return debitAccountName; }
    public void setDebitAccountName(String v)         { this.debitAccountName = v; }

    public String getCreditBankCode()                 { return creditBankCode; }
    public void setCreditBankCode(String v)           { this.creditBankCode = v; }

    public String getCreditBankName()                 { return creditBankName; }
    public void setCreditBankName(String v)           { this.creditBankName = v; }

    public String getCreditAccountNumber()            { return creditAccountNumber; }
    public void setCreditAccountNumber(String v)      { this.creditAccountNumber = v; }

    public String getCreditAccountName()              { return creditAccountName; }
    public void setCreditAccountName(String v)        { this.creditAccountName = v; }

    public String getAmount()                         { return amount; }
    public void setAmount(String v)                   { this.amount = v; }

    public String getReference()                      { return reference; }
    public void setReference(String v)                { this.reference = v; }

    public String getNarration()                      { return narration; }
    public void setNarration(String v)                { this.narration = v; }
}
