package com.moniewise.moniewise_backend.psp.payeelord.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/**
 * Wire request body for {@code POST /api/buy/airtime}.
 *
 * <p>Per the real documented request both the airtime and data endpoints use
 * camelCase {@code mobileNumber}, and {@code amount} is sent as a STRING
 * (e.g. {@code "amount": "10"}). We accept a {@link BigDecimal} in the
 * constructor for type safety and serialize it as a plain string to match.
 *
 * <pre>{@code
 * { "network": "MTN", "amount": "10", "mobileNumber": "08144446509" }
 * }</pre>
 */
public class PayeelordAirtimePurchaseRequest {

    /** MTN | GLO | AIRTEL | 9MOBILE */
    @JsonProperty("network")
    private String network;

    @JsonProperty("mobileNumber")
    private String mobileNumber;

    /** Naira amount sent as a plain string (e.g. "10") to match the documented contract. */
    @JsonProperty("amount")
    private String amount;

    public PayeelordAirtimePurchaseRequest() {}

    public PayeelordAirtimePurchaseRequest(String network, String mobileNumber, BigDecimal amount) {
        this.network = network;
        this.mobileNumber = mobileNumber;
        this.amount = amount != null ? amount.stripTrailingZeros().toPlainString() : null;
    }

    // ── Getters & Setters ─────────────────────────────────────────────────────

    public String getNetwork()                   { return network; }
    public void setNetwork(String network)       { this.network = network; }

    public String getMobileNumber()               { return mobileNumber; }
    public void setMobileNumber(String v)         { this.mobileNumber = v; }

    public String getAmount()                     { return amount; }
    public void setAmount(String amount)          { this.amount = amount; }
}
