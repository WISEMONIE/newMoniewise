package com.moniewise.moniewise_backend.dto.request;

import javax.validation.constraints.NotBlank;

public class UpdateBankDetailsRequest {
    @NotBlank(message = "Bank Code is required")
    private String bankCode;

    @NotBlank(message = "Bank Name is required")
    private String bankName;

    @NotBlank(message = "Account Number is required")
    private String accountNumber;

    // --- Getters and Setters ---
    public String getBankCode() { return bankCode; }
    public void setBankCode(String bankCode) { this.bankCode = bankCode; }

    public String getBankName() { return bankName; }
    public void setBankName(String bankName) { this.bankName = bankName; }

    public String getAccountNumber() { return accountNumber; }
    public void setAccountNumber(String accountNumber) { this.accountNumber = accountNumber; }
}