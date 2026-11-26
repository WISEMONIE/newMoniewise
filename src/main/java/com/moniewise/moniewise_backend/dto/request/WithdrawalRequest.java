package com.moniewise.moniewise_backend.dto.request;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.math.BigDecimal;

public class WithdrawalRequest {

    @NotNull(message = "Amount is required")
    @DecimalMin(value = "100.0", message = "Minimum withdrawal is ₦100")
    private BigDecimal amount;

    @NotBlank(message = "Transaction PIN is required")
    private String transactionPin;

    @Size(max = 120, message = "Narration must not exceed 120 characters")
    private String narration;

    // ── Destination bank details (required for Rubies, ignored for legacy PSPs) ──
    // Frontend flow: GET /wallets/banks → POST /wallets/resolve-account → POST /wallets/withdraw
    // The user picks a bank, enters an account number, the name is verified, then they confirm.
    private String bankCode;       // e.g. "058"
    private String bankName;       // e.g. "GTBank"
    private String accountNumber;  // recipient account number
    private String accountName;    // resolved via /wallets/resolve-account — must match

    // Set only by the account-closure flow. Switches this single withdrawal to
    // the flat closure charge (NIP paid out of it, remainder to Moniewise) so
    // the total debit lands exactly on the user's balance. Ordinary transfers
    // leave this false and keep tiered pricing.
    private boolean closure;

    // Getters and Setters
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getTransactionPin() { return transactionPin; }
    public void setTransactionPin(String transactionPin) { this.transactionPin = transactionPin; }

    public String getNarration() { return narration; }
    public void setNarration(String narration) { this.narration = narration; }

    public String getBankCode() { return bankCode; }
    public void setBankCode(String bankCode) { this.bankCode = bankCode; }

    public String getBankName() { return bankName; }
    public void setBankName(String bankName) { this.bankName = bankName; }

    public String getAccountNumber() { return accountNumber; }
    public void setAccountNumber(String accountNumber) { this.accountNumber = accountNumber; }

    public String getAccountName() { return accountName; }
    public void setAccountName(String accountName) { this.accountName = accountName; }

    public boolean isClosure() { return closure; }
    public void setClosure(boolean closure) { this.closure = closure; }
}
