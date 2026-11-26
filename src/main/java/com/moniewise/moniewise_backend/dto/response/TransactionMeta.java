package com.moniewise.moniewise_backend.dto.response;

import com.moniewise.moniewise_backend.enums.TransactionCategory;

public record TransactionMeta(
        String sourceEnvelopeName,
        String targetEnvelopeName,
        String budgetName,
        Boolean isOutgoing,
        TransactionCategory category
) {}