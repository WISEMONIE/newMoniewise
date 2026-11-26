package com.moniewise.moniewise_backend.enums;

public enum BudgetStatus {
    DRAFT, SCHEDULED, ACTIVE, COMPLETED, FAILED_PROCESSING,
    // Soft-deleted / dissolved by the user. Rows are kept for audit and
    // regulatory retention; the scheduler ignores non-ACTIVE budgets and
    // user-facing lists exclude CANCELLED.
    CANCELLED
}
