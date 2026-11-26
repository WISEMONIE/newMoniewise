CREATE TABLE IF NOT EXISTS badges (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(100),
    name VARCHAR(255) NOT NULL,
    description VARCHAR(255),
    category VARCHAR(60),
    threshold INTEGER NOT NULL DEFAULT 1,
    icon_url VARCHAR(255),
    share_title VARCHAR(255),
    share_message TEXT,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

ALTER TABLE badges ADD COLUMN IF NOT EXISTS code VARCHAR(100);
ALTER TABLE badges ADD COLUMN IF NOT EXISTS category VARCHAR(60);
ALTER TABLE badges ADD COLUMN IF NOT EXISTS share_title VARCHAR(255);
ALTER TABLE badges ADD COLUMN IF NOT EXISTS share_message TEXT;
ALTER TABLE badges ALTER COLUMN threshold SET DEFAULT 1;
UPDATE badges SET threshold = 1 WHERE threshold IS NULL;
UPDATE badges SET active = TRUE WHERE active IS NULL;
UPDATE badges SET code = CONCAT('LEGACY_', id) WHERE code IS NULL OR BTRIM(code) = '';
CREATE UNIQUE INDEX IF NOT EXISTS uk_badges_code ON badges (code);

CREATE TABLE IF NOT EXISTS user_badges (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    badge_id BIGINT NOT NULL,
    source_type VARCHAR(40) NOT NULL DEFAULT 'LEGACY',
    source_id BIGINT NOT NULL DEFAULT 0,
    title VARCHAR(255),
    message TEXT,
    share_title VARCHAR(255),
    share_message TEXT,
    earned_at TIMESTAMPTZ DEFAULT NOW(),
    seen_at TIMESTAMPTZ,
    CONSTRAINT fk_user_badges_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_user_badges_badge FOREIGN KEY (badge_id) REFERENCES badges(id)
);

ALTER TABLE user_badges ADD COLUMN IF NOT EXISTS source_type VARCHAR(40) NOT NULL DEFAULT 'LEGACY';
ALTER TABLE user_badges ADD COLUMN IF NOT EXISTS source_id BIGINT NOT NULL DEFAULT 0;
ALTER TABLE user_badges ADD COLUMN IF NOT EXISTS title VARCHAR(255);
ALTER TABLE user_badges ADD COLUMN IF NOT EXISTS message TEXT;
ALTER TABLE user_badges ADD COLUMN IF NOT EXISTS share_title VARCHAR(255);
ALTER TABLE user_badges ADD COLUMN IF NOT EXISTS share_message TEXT;
ALTER TABLE user_badges ADD COLUMN IF NOT EXISTS seen_at TIMESTAMPTZ;
UPDATE user_badges SET source_type = 'LEGACY' WHERE source_type IS NULL OR BTRIM(source_type) = '';
UPDATE user_badges SET source_id = 0 WHERE source_id IS NULL;
ALTER TABLE user_badges ALTER COLUMN source_type SET DEFAULT 'LEGACY';
ALTER TABLE user_badges ALTER COLUMN source_id SET DEFAULT 0;
ALTER TABLE user_badges ALTER COLUMN source_type SET NOT NULL;
ALTER TABLE user_badges ALTER COLUMN source_id SET NOT NULL;

DO $$
DECLARE
    constraint_name TEXT;
BEGIN
    FOR constraint_name IN
        SELECT c.conname
        FROM pg_constraint c
        WHERE c.conrelid = 'user_badges'::regclass
          AND c.contype = 'u'
          AND (
              SELECT STRING_AGG(a.attname, ',' ORDER BY key_column.ordinality)
              FROM UNNEST(c.conkey) WITH ORDINALITY AS key_column(attnum, ordinality)
              JOIN pg_attribute a
                ON a.attrelid = c.conrelid
               AND a.attnum = key_column.attnum
          ) = 'user_id,badge_id'
    LOOP
        EXECUTE 'ALTER TABLE user_badges DROP CONSTRAINT ' || QUOTE_IDENT(constraint_name);
    END LOOP;
END $$;

DO $$
DECLARE
    index_name TEXT;
BEGIN
    FOR index_name IN
        SELECT i.relname
        FROM pg_index ix
        JOIN pg_class i ON i.oid = ix.indexrelid
        JOIN pg_class t ON t.oid = ix.indrelid
        WHERE t.relname = 'user_badges'
          AND ix.indisunique
          AND NOT EXISTS (
              SELECT 1
              FROM pg_constraint c
              WHERE c.conindid = ix.indexrelid
          )
          AND (
              SELECT STRING_AGG(a.attname, ',' ORDER BY key_column.ordinality)
              FROM UNNEST(ix.indkey) WITH ORDINALITY AS key_column(attnum, ordinality)
              JOIN pg_attribute a
                ON a.attrelid = ix.indrelid
               AND a.attnum = key_column.attnum
          ) = 'user_id,badge_id'
    LOOP
        EXECUTE 'DROP INDEX IF EXISTS ' || QUOTE_IDENT(index_name);
    END LOOP;
END $$;

DELETE FROM user_badges newer
USING user_badges older
WHERE newer.id > older.id
  AND newer.user_id = older.user_id
  AND newer.badge_id = older.badge_id
  AND newer.source_type = older.source_type
  AND newer.source_id = older.source_id;

CREATE UNIQUE INDEX IF NOT EXISTS uk_user_badges_source
    ON user_badges (user_id, badge_id, source_type, source_id);

INSERT INTO badges (code, name, description, category, threshold, icon_url, share_title, share_message, active, created_at)
SELECT
    'BUDGET_COMPLETED',
    'Budget Finisher',
    'Completed a Wisemonie budget.',
    'BUDGET',
    1,
    NULL,
    'Budget completed',
    'I completed a Wisemonie budget.',
    TRUE,
    NOW()
WHERE NOT EXISTS (SELECT 1 FROM badges WHERE code = 'BUDGET_COMPLETED');

INSERT INTO badges (code, name, description, category, threshold, icon_url, share_title, share_message, active, created_at)
SELECT
    'SAVINGS_MATURED',
    'Savings Finisher',
    'Completed a Wisemonie savings goal.',
    'SAVINGS',
    1,
    NULL,
    'Savings goal completed',
    'I completed a Wisemonie savings goal.',
    TRUE,
    NOW()
WHERE NOT EXISTS (SELECT 1 FROM badges WHERE code = 'SAVINGS_MATURED');
