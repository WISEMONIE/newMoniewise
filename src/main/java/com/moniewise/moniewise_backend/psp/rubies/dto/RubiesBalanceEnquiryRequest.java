package com.moniewise.moniewise_backend.psp.rubies.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request body for POST /{stage}/baas-wallet/balance-enquiry
 */
public class RubiesBalanceEnquiryRequest {

    @JsonProperty("accountNumber")
    private String accountNumber;

    public RubiesBalanceEnquiryRequest() {}

    public RubiesBalanceEnquiryRequest(String accountNumber) {
        this.accountNumber = accountNumber;
    }

    public String getAccountNumber()           { return accountNumber; }
    public void setAccountNumber(String v)     { this.accountNumber = v; }
}
