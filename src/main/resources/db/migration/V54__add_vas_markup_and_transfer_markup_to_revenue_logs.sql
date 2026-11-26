-- V54: Add 'vas_markup' and 'transfer_markup_fee' to revenue_logs type constraint.
-- vas_markup: tracks the margin earned on each successful airtime/data purchase.
-- transfer_markup_fee: already used by WalletService but was never added to the constraint.

ALTER TABLE revenue_logs
    DROP CONSTRAINT IF EXISTS revenue_logs_type_check;

ALTER TABLE revenue_logs
    ADD CONSTRAINT revenue_logs_type_check CHECK (
        type IN (
            'budget_creation',
            'budget_creation_fee',
            'movement_fee',
            'emergency_fee',
            'envelope_transfer_fee',
            'envelope_external_transfer_fee',
            'transfer_markup_fee',
            'vas_markup'
        )
    );
