CREATE INDEX IF NOT EXISTS idx_budgets_scheduled_activation
    ON budgets (start_date, id)
    WHERE status = 'SCHEDULED';
