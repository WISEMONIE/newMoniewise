-- Make the budget creation minimum runtime-configurable.
-- Product can lower this (for example to 1000 or 2000) through /admin/config without redeploying.
INSERT INTO system_config (config_key, config_value, description) VALUES
    ('budget.min.amount', '5000', 'Minimum amount a user can use to create a budget in NGN.')
ON CONFLICT (config_key) DO NOTHING;
