ALTER TABLE wallets
    ADD COLUMN IF NOT EXISTS account_name VARCHAR(255);

UPDATE wallets
SET account_name = COALESCE(
        NULLIF(BTRIM(provider_metadata ->> 'accountName'), ''),
        NULLIF(BTRIM(provider_metadata ->> 'account_name'), '')
    )
WHERE (account_name IS NULL OR BTRIM(account_name) = '')
  AND provider_metadata IS NOT NULL;
