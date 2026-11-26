-- Adds the columns that store the identity data returned by the SecureWave
-- BVN Verification API (POST /api/verify-bvn).
--
-- All columns are nullable so that existing rows (profiles created before
-- BVN verification was wired up) are not invalidated by this migration.
--
-- Each ADD COLUMN is guarded by IF NOT EXISTS so the script is safe to
-- re-run in environments that may have already applied the schema via
-- ddl-auto=update.

DO $$
BEGIN

    -- ── SecureWave top-level fields ──────────────────────────────────────────

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'kyc_profiles' AND column_name = 'name_on_card'
    ) THEN
        ALTER TABLE kyc_profiles ADD COLUMN name_on_card VARCHAR(255);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'kyc_profiles' AND column_name = 'enrolment_bank'
    ) THEN
        ALTER TABLE kyc_profiles ADD COLUMN enrolment_bank VARCHAR(255);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'kyc_profiles' AND column_name = 'enrolment_branch'
    ) THEN
        ALTER TABLE kyc_profiles ADD COLUMN enrolment_branch VARCHAR(255);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'kyc_profiles' AND column_name = 'formatted_registration_date'
    ) THEN
        ALTER TABLE kyc_profiles ADD COLUMN formatted_registration_date VARCHAR(100);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'kyc_profiles' AND column_name = 'level_of_account'
    ) THEN
        ALTER TABLE kyc_profiles ADD COLUMN level_of_account VARCHAR(100);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'kyc_profiles' AND column_name = 'nin'
    ) THEN
        ALTER TABLE kyc_profiles ADD COLUMN nin VARCHAR(50);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'kyc_profiles' AND column_name = 'watchlisted'
    ) THEN
        ALTER TABLE kyc_profiles ADD COLUMN watchlisted VARCHAR(10);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'kyc_profiles' AND column_name = 'bvn_verification_status'
    ) THEN
        ALTER TABLE kyc_profiles ADD COLUMN bvn_verification_status VARCHAR(50);
    END IF;

    -- ── personal_info fields ─────────────────────────────────────────────────

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'kyc_profiles' AND column_name = 'first_name'
    ) THEN
        ALTER TABLE kyc_profiles ADD COLUMN first_name VARCHAR(255);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'kyc_profiles' AND column_name = 'middle_name'
    ) THEN
        ALTER TABLE kyc_profiles ADD COLUMN middle_name VARCHAR(255);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'kyc_profiles' AND column_name = 'last_name'
    ) THEN
        ALTER TABLE kyc_profiles ADD COLUMN last_name VARCHAR(255);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'kyc_profiles' AND column_name = 'gender'
    ) THEN
        ALTER TABLE kyc_profiles ADD COLUMN gender VARCHAR(50);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'kyc_profiles' AND column_name = 'date_of_birth'
    ) THEN
        ALTER TABLE kyc_profiles ADD COLUMN date_of_birth VARCHAR(100);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'kyc_profiles' AND column_name = 'state_of_origin'
    ) THEN
        ALTER TABLE kyc_profiles ADD COLUMN state_of_origin VARCHAR(255);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'kyc_profiles' AND column_name = 'lga_of_origin'
    ) THEN
        ALTER TABLE kyc_profiles ADD COLUMN lga_of_origin VARCHAR(255);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'kyc_profiles' AND column_name = 'nationality'
    ) THEN
        ALTER TABLE kyc_profiles ADD COLUMN nationality VARCHAR(100);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'kyc_profiles' AND column_name = 'marital_status'
    ) THEN
        ALTER TABLE kyc_profiles ADD COLUMN marital_status VARCHAR(100);
    END IF;

    -- ── residential_info fields ──────────────────────────────────────────────

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'kyc_profiles' AND column_name = 'state_of_residence'
    ) THEN
        ALTER TABLE kyc_profiles ADD COLUMN state_of_residence VARCHAR(255);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'kyc_profiles' AND column_name = 'lga_of_residence'
    ) THEN
        ALTER TABLE kyc_profiles ADD COLUMN lga_of_residence VARCHAR(255);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'kyc_profiles' AND column_name = 'residential_address'
    ) THEN
        ALTER TABLE kyc_profiles ADD COLUMN residential_address VARCHAR(500);
    END IF;

END;
$$;

-- Index for fast watchlist checks (risk engine queries on this)
CREATE INDEX IF NOT EXISTS idx_kyc_watchlisted
    ON kyc_profiles (watchlisted)
    WHERE watchlisted IS NOT NULL;

-- Index for status-based queries (dashboard, admin, risk engine)
CREATE INDEX IF NOT EXISTS idx_kyc_bvn_verification_status
    ON kyc_profiles (bvn_verification_status)
    WHERE bvn_verification_status IS NOT NULL;
