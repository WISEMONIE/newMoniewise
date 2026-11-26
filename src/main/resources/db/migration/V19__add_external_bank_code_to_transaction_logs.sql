-- V19: Store the destination bank code on envelope external-transfer logs.
--
-- Prior to this migration, TransactionLog only stored externalBankName for EXT-
-- transfers; externalBankCode was never persisted.  Without the bank code the
-- GET /wallets/recent-recipients endpoint could not include EXT- envelope
-- transfers in the auto-suggest list (bank code is required to auto-fill the
-- Rubies bank picker and to drive account-name resolution on re-use).
--
-- Nullable because:
--   • All historical EXT- rows will have NULL here (back-fill is not possible
--     without replaying the original request).
--   • Non-EXT rows (P2P, wallet withdrawals, etc.) never have a bank code.
--
-- New transfers created after this migration will always carry the bank code.

ALTER TABLE transaction_logs
    ADD COLUMN IF NOT EXISTS external_bank_code VARCHAR(20);
