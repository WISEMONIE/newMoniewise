-- V49: Add 'budget_creation_fee' to the revenue_logs.type check constraint.
-- The budget creation flow now logs the fee under this more specific type
-- (distinct from the original 'budget_creation' used for the budget amount itself).

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
            'envelope_external_transfer_fee'
        )
    );
