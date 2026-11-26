package com.moniewise.moniewise_backend.psp.rubies.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Inbound webhook payload from Rubies MFB.
 *
 * <p>Rubies POSTs a FLAT JSON object to the registered callback URL on every
 * transaction event. There is NO nested {@code data} wrapper and NO {@code event}
 * field — the event type must be synthesised from {@code drCr} + {@code responseCode}.
 *
 * <p>Sample credit (deposit) payload:
 * <pre>
 * {
 *   "paymentReference":       "TXN-...",
 *   "creditAccount":          "1234567890",
 *   "originatorAccountNumber":"0987654321",
 *   "drCr":                   "CR",
 *   "amount":                 "5000.00",
 *   "narration":              "Transfer from John",
 *   "responseCode":           "00",
 *   "sessionId":              "...",
 *   "bankName":               "GTBank",
 *   "bankCode":               "058",
 *   "originatorName":         "JOHN DOE",
 *   "creditAccountName":      "JANE DOE",
 *   "service":                "NIP"
 * }
 * </pre>
 *
 * <p>Sample debit (outbound transfer) payload — same structure, {@code drCr} = "DR".
 *
 * <p>Authenticate via HMAC-SHA512 of the raw body using the
 * {@code RUBIES_WEBHOOK_SECRET} env var against the {@code X-Rubies-Signature} header.
 *
 * <h3>Synthesised event type</h3>
 * <ul>
 *   <li>{@code drCr=DR} + {@code responseCode=00}  → {@code TRANSFER.SUCCESS}</li>
 *   <li>{@code drCr=DR} + {@code responseCode≠00}  → {@code TRANSFER.FAILED}</li>
 *   <li>{@code drCr=CR} + {@code responseCode=00}  → {@code CREDIT.RECEIVED}</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RubiesWebhookPayload {

    // ── Core fields ───────────────────────────────────────────────────────────

    /** Our reference that we sent as {@code transactionReference} in the fund-transfer call. */
    @JsonProperty("paymentReference")
    private String paymentReference;

    /** Account number that was credited (matches our wallet's accountNumber for deposits). */
    @JsonProperty("creditAccount")
    private String creditAccount;

    /** Account number that was debited (sender's account). */
    @JsonProperty("originatorAccountNumber")
    private String originatorAccountNumber;

    /**
     * Direction of the transaction from the perspective of the Rubies platform:
     * {@code "CR"} = credit to {@code creditAccount}, {@code "DR"} = debit of origin.
     */
    @JsonProperty("drCr")
    private String drCr;

    /** Amount as a string (e.g. "5000.00"). */
    @JsonProperty("amount")
    private String amount;

    @JsonProperty("narration")
    private String narration;

    /** Rubies result code — {@code "00"} means success, anything else is an error. */
    @JsonProperty("responseCode")
    private String responseCode;

    @JsonProperty("responseMessage")
    private String responseMessage;

    /** NIP session ID (also used as provider reference for completed transfers). */
    @JsonProperty("sessionId")
    private String sessionId;

    // ── Counterparty bank details ─────────────────────────────────────────────

    @JsonProperty("bankName")
    private String bankName;

    @JsonProperty("bankCode")
    private String bankCode;

    @JsonProperty("originatorName")
    private String originatorName;

    @JsonProperty("creditAccountName")
    private String creditAccountName;

    /** Service type, e.g. "NIP". */
    @JsonProperty("service")
    private String service;

    // ── Convenience helpers ───────────────────────────────────────────────────

    public boolean isSuccessCode() {
        return "00".equals(responseCode);
    }

    public boolean isCreditEvent() {
        return "CR".equalsIgnoreCase(drCr);
    }

    public boolean isDebitEvent() {
        return "DR".equalsIgnoreCase(drCr);
    }

    // ── Getters & Setters ─────────────────────────────────────────────────────

    public String getPaymentReference()               { return paymentReference; }
    public void setPaymentReference(String v)         { this.paymentReference = v; }

    public String getCreditAccount()                  { return creditAccount; }
    public void setCreditAccount(String v)            { this.creditAccount = v; }

    public String getOriginatorAccountNumber()        { return originatorAccountNumber; }
    public void setOriginatorAccountNumber(String v)  { this.originatorAccountNumber = v; }

    public String getDrCr()                           { return drCr; }
    public void setDrCr(String v)                     { this.drCr = v; }

    public String getAmount()                         { return amount; }
    public void setAmount(String v)                   { this.amount = v; }

    public String getNarration()                      { return narration; }
    public void setNarration(String v)                { this.narration = v; }

    public String getResponseCode()                   { return responseCode; }
    public void setResponseCode(String v)             { this.responseCode = v; }

    public String getResponseMessage()                { return responseMessage; }
    public void setResponseMessage(String v)          { this.responseMessage = v; }

    public String getSessionId()                      { return sessionId; }
    public void setSessionId(String v)                { this.sessionId = v; }

    public String getBankName()                       { return bankName; }
    public void setBankName(String v)                 { this.bankName = v; }

    public String getBankCode()                       { return bankCode; }
    public void setBankCode(String v)                 { this.bankCode = v; }

    public String getOriginatorName()                 { return originatorName; }
    public void setOriginatorName(String v)           { this.originatorName = v; }

    public String getCreditAccountName()              { return creditAccountName; }
    public void setCreditAccountName(String v)        { this.creditAccountName = v; }

    public String getService()                        { return service; }
    public void setService(String v)                  { this.service = v; }
}
