package com.moniewise.moniewise_backend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class WalletResponse {
    BigDecimal balance;
    String currency;
    String accountNumber;
    String bankName;
    String accountName;
    String status;
    LocalDateTime updatedAt;
    /**
     * The PSP that manages this wallet — e.g. "RUBIES", "PROVIDUS", "SECUREWAVE".
     * Frontend uses this to branch the UI:
     *  - RUBIES  → OPay-style transfer form (pick bank, enter account number per transfer)
     *  - others  → legacy pre-linked settlement account form
     */
    String providerName;
    BigDecimal totalHoldings;

    /**
     * Only present when money out is limited: frozen, under review, or the
     * first-day limit. The app shows a banner from it. Absent for everyone else.
     */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    java.util.Map<String, Object> restriction;
}
