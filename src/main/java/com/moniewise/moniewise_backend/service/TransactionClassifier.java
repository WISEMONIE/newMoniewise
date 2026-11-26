package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.enums.TransactionCategory;
import com.moniewise.moniewise_backend.enums.TransactionType;

public class TransactionClassifier {

    public static TransactionCategory categoryOf(TransactionType type) {
        return switch (type) {

            // INTERNAL (money stays inside user's budget/wallet)
            case WALLET_DEPOSIT,
                 WALLET_TO_BUDGET,
                 BUDGET_ALLOCATION,
                 ENVELOPE_TO_ENVELOPE,
                 USER_TO_ENVELOPE,
                 BUDGET_UNALLOCATED_REFUNDED
                    -> TransactionCategory.INTERNAL;

            // TO BANK
            case ENVELOPE_TO_EXTERNAL
                    -> TransactionCategory.TO_EXTERNAL_BANK;

            // TO OTHER USERS
            case ENVELOPE_TO_USER
                    -> TransactionCategory.TO_MONIEWISE_USER;
            default -> throw new IllegalStateException("Unexpected value: " + type);
        };
    }
}


