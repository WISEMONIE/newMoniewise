-- V17: Expand the revenue_logs.type check constraint to include the new
-- 'envelope_external_transfer_fee' type used by ExternalTransferSettlementService
-- when crediting the Moniewise revenue wallet on confirmed envelope → bank transfers.
--
-- The old constraint only covered the four original types:
--   budget_creation, movement_fee, emergency_fee, envelope_transfer_fee
--
-- We DROP and re-ADD the constraint (ALTER TABLE … DROP CONSTRAINT + ADD CONSTRAINT)
-- because PostgreSQL has no ALTER CONSTRAINT syntax for check constraints.

ALTER TABLE revenue_logs
    DROP CONSTRAINT IF EXISTS revenue_logs_type_check;

ALTER TABLE revenue_logs
    ADD CONSTRAINT revenue_logs_type_check CHECK (
        type IN (
            'budget_creation',
            'movement_fee',
            'emergency_fee',
            'envelope_transfer_fee',
            'envelope_external_transfer_fee'
        )
    );
