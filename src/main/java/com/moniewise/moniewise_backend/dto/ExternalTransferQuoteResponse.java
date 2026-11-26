package com.moniewise.moniewise_backend.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class ExternalTransferQuoteResponse {

    private Long envelopeId;
    private BigDecimal amount;
    /**
     * Moniewise markup fee — the portion that enters Moniewise revenue.
     * Does NOT include the NIP bank charge.
     */
    private BigDecimal fee;
    /**
     * NIBSS NIP interbank bank charge (e.g. ₦10.75 / ₦26.88 / ₦53.75).
     * Charged by Rubies at BaaS level and goes to the banking system.
     * Moniewise does NOT collect this — it is shown here for user transparency.
     * Zero for non-Rubies providers.
     */
    private BigDecimal bankCharge;
    /**
     * Nigerian stamp duty — ₦50 flat charge on transfers above ₦10,000.
     * Charged by the banking system, NOT Moniewise revenue. Zero for ≤ ₦10K.
     */
    private BigDecimal stampDuty;
    /** = amount + bankCharge + fee + stampDuty */
    private BigDecimal totalDebit;
    private BigDecimal recipientReceives;
    private String providerName;
    private String feePolicy;
    private String feeSource;
    private String message;
    private String bankName;
    private String accountNumber;
    private String accountName;
}