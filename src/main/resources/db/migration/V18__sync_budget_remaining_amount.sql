-- V18: Sync budgets.remaining_amount to match the actual money still sitting in envelopes.
--
-- Root cause:
--   ExternalTransferSettlementService.settleExternalTransfer() correctly reduced the
--   source envelope's totalRemainingAmount when a transfer was confirmed, but never
--   touched the parent budget's remaining_amount.  As a result, any budget whose
--   envelopes had at least one settled external transfer shows a stale (too-high)
--   remaining_amount in the DB.
--
-- Fix:
--   Set remaining_amount = SUM(envelope.total_remaining_amount) for every budget.
--   This is always the ground truth regardless of how old the budget is or how many
--   transfers it has had.  Non-deleted envelopes only; savings-swept envelopes already
--   carry total_remaining_amount = 0 so they are handled correctly by the SUM.
--
-- Application-side fix (deployed together with this migration):
--   ExternalTransferSettlementService now calls budgetRepository.save() after reducing
--   totalRemainingAmount, keeping remaining_amount in sync going forward.

UPDATE budgets b
SET remaining_amount = (
    SELECT COALESCE(SUM(e.total_remaining_amount), 0)
    FROM   envelopes e
    WHERE  e.budget_id = b.id
      AND  e.deleted_at IS NULL
);
