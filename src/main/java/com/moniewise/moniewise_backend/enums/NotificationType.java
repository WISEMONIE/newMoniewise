package com.moniewise.moniewise_backend.enums;

public enum NotificationType {
    MATURITY_ALERT,
    BUDGET_LIMIT_WARNING,
    BUDGET_COMPLETED,
    WELCOME,
    BUDGET_END,
    PRE_DISBURSEMENT,
    DISBURSEMENT,
    WITHDRAWAL,
    EXPIRED_DISBURSEMENT,
    ENVELOPE_TRANSFER,
    EXTERNAL_TRANSFER,
    INSUFFICIENT_BALANCE,
    BUDGET_CREATION_FEE,
    WALLET_FUNDED,
    BUDGET_CREATION,
    BUDGET_SCHEDULED,
    BUDGET_ACTIVATED,
    ENVELOPE_CREATED,
    ENVELOPE_UPDATED,
    ENVELOPE_DELETED,
    BUDGET_END_SOON,           // 3 days left
    BUDGET_ENDS_TODAY,         // 0 days left — the actual last day
    DISBURSEMENT_SUCCESS,      // Money released
    DISBURSEMENT_FAILED,       // Grace period expired
    LIMIT_REACHED,             // Daily/weekly cap
    EMERGENCY_USED,
    WEEKLY_SUMMARY,
    POSITIVE_NUDGE,
    BUDGET_ENGAGEMENT_NUDGE,
    REFUND_ISSUED,
    BUDGET_EXPIRED,
    ENVELOPE_LOCKED,
    ENVELOPE_UNLOCKED,
    LOW_BALANCE_WARNING,
    GOAL_ACHIEVED,
    BUDGET_UPDATED,
    DISBURSEMENT_REMINDER,
    SALARY_WEEK_NUDGE,
    POST_SALARY_NUDGE,
    MID_MONTH_NUDGE,
    SPECIAL_OCCASION_NUDGE,
    BIRTHDAY_NUDGE,
    HOW_TO_USE_WISEMONIE,
    SIGNUP_RETURN_NUDGE,
    SYSTEM,
    SERVICE_OUTAGE,
    SCHEDULED_MAINTENANCE,
    SPECIAL_ANNOUNCEMENT,
    BUDGET_CREATION_SUCCESS,
    WALLET_DEPOSIT,
    DISBURSEMENT_READY,
    BUDGET_ENDING_SOON,
    ENVELOPE_LOW_BALANCE,
    DISBURSEMENT_REFUNDED,
    BUDGET_UNALLOCATED_REFUNDED,
    SAVINGS_MATURED,
    SAVINGS_MATURING_SOON,     // 7 days left
    SAVINGS_GOAL_CREATED,      // a new savings goal was opened
    SAVINGS_DEPOSIT,           // top-up / envelope-sweep into a savings goal

    // ── Payeelord VAS (airtime & data) ──────────────────────────────────────
    AIRTIME_PURCHASE_SUCCESS,
    AIRTIME_PURCHASE_FAILED,
    DATA_PURCHASE_SUCCESS,
    DATA_PURCHASE_FAILED,

    // ── Onboarding recovery ────────────────────────────────────────────────
    ONBOARDING_REMINDER,

    // ── Envelope auto-transfer ────────────────────────────────────────────
    AUTO_TRANSFER_SUCCESS,
    AUTO_TRANSFER_FAILED,
    AUTO_TRANSFER_INSUFFICIENT_FUNDS,

    // ── Admin / ops alerts ──────────────────────────────────────────────────
    ADMIN_PAYEELORD_LOW_BALANCE,
    ADMIN_RECONCILIATION_ALERT
}
