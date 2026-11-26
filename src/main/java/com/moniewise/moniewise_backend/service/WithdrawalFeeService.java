package com.moniewise.moniewise_backend.service;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
public class WithdrawalFeeService {

    private static final BigDecimal WITHDRAWAL_FEE_NAIRA = BigDecimal.valueOf(15);

    public BigDecimal calculateWithdrawalFee(BigDecimal amount) {
        validateAmount(amount);
        return WITHDRAWAL_FEE_NAIRA;
    }

    public BigDecimal calculateTotalDebit(BigDecimal amount) {
        validateAmount(amount);
        return amount.add(calculateWithdrawalFee(amount));
    }

    private void validateAmount(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Withdrawal amount must be greater than zero.");
        }
    }
}
