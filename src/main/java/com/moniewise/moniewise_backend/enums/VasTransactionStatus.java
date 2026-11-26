package com.moniewise.moniewise_backend.enums;

/**
 * Lifecycle status of a Payeelord airtime/data purchase.
 *
 * <p><strong>Important — this is a SYNCHRONOUS integration</strong> (unlike Rubies
 * bank transfers). Payeelord returns {@code successful}/{@code failed} directly in
 * the HTTP response, so most transactions resolve to {@link #SUCCESSFUL} or
 * {@link #FAILED} — followed immediately by {@link #REVERSED} if the purchase
 * itself failed but the wallet had already been debited.
 *
 * <p>{@link #PENDING} only covers the brief in-flight window of the HTTP call
 * itself, or the rare case where Payeelord responds with {@code "processing"}
 * (their docs list it as a valid status — meaning "accepted, still resolving
 * upstream"). A pending transaction should be reconciled via
 * {@code GET /api/data-transactions} rather than trusted indefinitely.
 */
public enum VasTransactionStatus {
    /** In-flight: wallet debited, waiting on Payeelord's HTTP response or "processing" resolution. */
    PENDING,
    /** Payeelord confirmed the purchase landed — final, user's recipient was credited. */
    SUCCESSFUL,
    /** Payeelord confirmed the purchase did not go through — the wallet debit must be reversed. */
    FAILED,
    /** The wallet debit was reversed after a failed purchase — the user was made whole. */
    REVERSED,
    /** Provider may have delivered, but backend cannot safely auto-confirm or auto-refund. */
    MANUAL_REVIEW
}
