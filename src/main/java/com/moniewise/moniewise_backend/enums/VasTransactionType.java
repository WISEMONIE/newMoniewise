package com.moniewise.moniewise_backend.enums;

/**
 * The kind of Value-Added-Service purchase made through Payeelord.
 *
 * <p>Scoped deliberately to just these two — Payeelord's broader catalog
 * (electricity, cable, e-pins, bulk SMS, etc.) is out of scope for this
 * integration per product decision.
 */
public enum VasTransactionType {
    AIRTIME,
    DATA
}
