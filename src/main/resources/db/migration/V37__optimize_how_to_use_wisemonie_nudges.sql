CREATE INDEX IF NOT EXISTS idx_users_how_to_use_wallet_guide
    ON users (created_at, id)
    WHERE is_deleted = false
      AND is_verified = true
      AND COALESCE(test_account, false) = false
      AND email IS NOT NULL
      AND btrim(email) <> '';

CREATE INDEX IF NOT EXISTS idx_wallets_how_to_use_active_wallet
    ON wallets (user_id, balance)
    WHERE status = 'ACTIVE'
      AND COALESCE(is_revenue_wallet, false) = false
      AND account_number IS NOT NULL
      AND btrim(account_number) <> '';

CREATE INDEX IF NOT EXISTS idx_budgets_how_to_use_active
    ON budgets (user_id)
    WHERE status = 'ACTIVE';

CREATE INDEX IF NOT EXISTS idx_engagement_how_to_use_recent
    ON engagement_nudge_notifications (user_id, sent_at DESC)
    WHERE campaign = 'HOW_TO_USE_WISEMONIE';
