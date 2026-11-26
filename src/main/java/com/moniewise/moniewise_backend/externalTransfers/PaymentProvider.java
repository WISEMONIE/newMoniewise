package com.moniewise.moniewise_backend.externalTransfers;

import com.moniewise.moniewise_backend.entity.User;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Service
public interface PaymentProvider {
    // 1. Verify the Name (e.g., "058" + "0123456789" -> "Emeka Johnson")
    String resolveAccount(String bankCode, String accountNumber);

    // 1. Account Creation
    Map<String, String> createVirtualAccount(User user);

    List<Map<String, Object>> getSupportedBanks();

    // 2. Send the Money
    String initiateTransfer(String bankCode, String accountNumber, String accountName, BigDecimal amount, String uniqueReference, String narration);

    boolean updateWithdrawalBankInfo(String email, String bankName, String accountName, String bankCode, String accountNumber);

    Map<String, Object> getWithdrawalBankInfo(String email);

    String initiateWithdrawal(String email, BigDecimal amount, String narration);
}
