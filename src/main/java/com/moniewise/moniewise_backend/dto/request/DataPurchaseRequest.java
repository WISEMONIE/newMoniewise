package com.moniewise.moniewise_backend.dto.request;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

/**
 * Inbound request to purchase a data bundle via Payeelord.
 *
 * <p>Deliberately minimal: the client only sends {@code dataId} (the catalog row
 * the user picked from {@code GET /vas/data/plans}) plus the recipient number and
 * PIN. We never trust client-supplied pricing — {@code networkId}, the plan's
 * face value, cost price and selling price are all looked up server-side from
 * {@code payeelord_data_plans} by {@code PayeelordPricingService}, exactly the
 * same "never trust the client with money fields" posture as
 * {@link WithdrawalRequest}.
 */
public class DataPurchaseRequest {

    @NotBlank(message = "Data plan is required")
    private String dataId;

    @NotBlank(message = "Recipient mobile number is required")
    @Size(min = 11, max = 20, message = "Mobile number must be between 11 and 20 digits")
    private String mobileNumber;

    /** Budget envelope the purchase is funded from. Null = fund from wallet balance. */
    private Long envelopeId;

    @NotBlank(message = "Transaction PIN is required")
    private String transactionPin;

    // Getters and Setters
    public String getDataId() { return dataId; }
    public void setDataId(String dataId) { this.dataId = dataId; }

    public Long getEnvelopeId() { return envelopeId; }
    public void setEnvelopeId(Long envelopeId) { this.envelopeId = envelopeId; }

    public String getMobileNumber() { return mobileNumber; }
    public void setMobileNumber(String mobileNumber) { this.mobileNumber = mobileNumber; }

    public String getTransactionPin() { return transactionPin; }
    public void setTransactionPin(String transactionPin) { this.transactionPin = transactionPin; }
}
