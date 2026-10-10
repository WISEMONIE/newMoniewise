-- V58: Update budget creation fee from ₦100 to ₦200 per 30-day interval
UPDATE system_configs
SET config_value = '200'
WHERE config_key = 'budget.creation.fee';
