ALTER TABLE withdrawals
    ADD COLUMN IF NOT EXISTS fee_amount NUMERIC(19, 2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS total_debit NUMERIC(19, 2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS recipient_receives NUMERIC(19, 2) NOT NULL DEFAULT 0;

UPDATE withdrawals
SET fee_amount = 0,
    total_debit = amount,
    recipient_receives = amount
WHERE total_debit = 0
   OR recipient_receives = 0;
