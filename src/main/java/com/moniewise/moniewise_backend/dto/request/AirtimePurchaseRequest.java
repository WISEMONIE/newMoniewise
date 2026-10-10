package com.moniewise.moniewise_backend.dto.request;

import javax.validation.constraints.DecimalMax;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Inbound request to purchase airtime via Payeelord.
 *
 * <p>Mirrors {@link WithdrawalRequest}'s shape: a PIN-gated financial action with
 * its own validation. The {@code amount} bounds (₦10 – ₦5,000) match Payeelord's
 * documented {@code POST /buy/airtime} limits exactly — we validate up front so a
 * bad request never reaches the gateway or touches the user's wallet.
 */
public class AirtimePurchaseRequest {

    @NotBlank(message = "Network is required")
    @Pattern(regexp = "MTN|GLO|AIRTEL|9MOBILE", message = "Network must be one of MTN, GLO, AIRTEL, 9MOBILE")
    private String network;

    @NotBlank(message = "Recipient mobile number is required")
    @Size(min = 11, max = 20, message = "Mobile number must be between 11 and 20 digits")
    private String mobileNumber;

    @NotNull(message = "Amount is required")
    @DecimalMin(value = "10", message = "Minimum airtime purchase is ₦10")
    @DecimalMax(value = "5000", message = "Maximum airtime purchase is ₦5,000")
    private BigDecimal amount;

    /** Budget envelope the purchase is funded from. Null = fund from wallet balance. */
    private Long envelopeId;

    @NotBlank(message = "Transaction PIN is required")
    private String transactionPin;

    // Getters and Setters
    public String getNetwork() { return network; }
    public void setNetwork(String network) { this.network = network; }

    public Long getEnvelopeId() { return envelopeId; }
    public void setEnvelopeId(Long envelopeId) { this.envelopeId = envelopeId; }

    public String getMobileNumber() { return mobileNumber; }
    public void setMobileNumber(String mobileNumber) { this.mobileNumber = mobileNumber; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getTransactionPin() { return transactionPin; }
    public void setTransactionPin(String transactionPin) { this.transactionPin = transactionPin; }
}
