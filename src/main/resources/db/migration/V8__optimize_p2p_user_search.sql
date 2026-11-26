CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX IF NOT EXISTS idx_users_search_email_trgm
    ON users USING gin (lower(coalesce(email, '')) gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_users_search_phone_trgm
    ON users USING gin (coalesce(phone, '') gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_users_search_first_name_trgm
    ON users USING gin (lower(coalesce(profile_data ->> 'firstName', '')) gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_users_search_last_name_trgm
    ON users USING gin (lower(coalesce(profile_data ->> 'lastName', '')) gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_users_search_user_tag_trgm
    ON users USING gin (lower(coalesce(profile_data ->> 'userTag', '')) gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_wallets_user_id
    ON wallets (user_id);
