CREATE INDEX IF NOT EXISTS idx_notifications_how_to_use_read
    ON notifications (user_id, is_read, created_at DESC)
    WHERE type = 'HOW_TO_USE_WISEMONIE';

CREATE INDEX IF NOT EXISTS idx_engagement_how_to_use_user_sent_date
    ON engagement_nudge_notifications (user_id, sent_date)
    WHERE campaign = 'HOW_TO_USE_WISEMONIE';

CREATE INDEX IF NOT EXISTS idx_wallets_user_funded_non_revenue
    ON wallets (user_id, balance)
    WHERE COALESCE(is_revenue_wallet, false) = false;
