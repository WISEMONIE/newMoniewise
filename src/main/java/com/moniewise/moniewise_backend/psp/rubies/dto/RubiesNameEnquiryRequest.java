package com.moniewise.moniewise_backend.psp.rubies.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request body for POST /{stage}/baas-transaction/name-enquiry
 */
public class RubiesNameEnquiryRequest {

    @JsonProperty("accountBankCode")
    private String accountBankCode;

    @JsonProperty("accountNumber")
    private String accountNumber;

    public RubiesNameEnquiryRequest() {}

    public RubiesNameEnquiryRequest(String accountBankCode, String accountNumber) {
        this.accountBankCode = accountBankCode;
        this.accountNumber   = accountNumber;
    }

    public String getAccountBankCode()             { return accountBankCode; }
    public void setAccountBankCode(String v)       { this.accountBankCode = v; }

    public String getAccountNumber()               { return accountNumber; }
    public void setAccountNumber(String v)         { this.accountNumber = v; }
}
