-- Pictures in admin pushes, and a record of every broadcast sent from the
-- admin site. Large sends run as a resumable job on this same row.

ALTER TABLE notifications ADD COLUMN IF NOT EXISTS image_url VARCHAR(1000);

CREATE TABLE IF NOT EXISTS admin_broadcasts (
    id                    BIGSERIAL PRIMARY KEY,
    type                  VARCHAR(40)   NOT NULL,
    title                 VARCHAR(120),
    message               VARCHAR(2000) NOT NULL,
    image_url             VARCHAR(1000),
    cta_label             VARCHAR(120),
    cta_url               VARCHAR(500),
    send_push             BOOLEAN       NOT NULL,
    send_email            BOOLEAN       NOT NULL,
    audience              VARCHAR(200)  NOT NULL,
    include_test_accounts BOOLEAN       NOT NULL,
    recipients            INTEGER       NOT NULL DEFAULT 0,
    push_queued           INTEGER       NOT NULL DEFAULT 0,
    emails_queued         INTEGER       NOT NULL DEFAULT 0,
    status                VARCHAR(20)   NOT NULL,
    sent_by               VARCHAR(200)  NOT NULL,
    created_at            TIMESTAMP     NOT NULL,
    finished_at           TIMESTAMP,
    -- One send per key: a double click or a retried request is not sent twice.
    request_key           VARCHAR(64),
    -- Background job: who to send to, where it got to, and who is running it.
    background            BOOLEAN       NOT NULL DEFAULT FALSE,
    all_users             BOOLEAN       NOT NULL DEFAULT FALSE,
    recipient_ids         TEXT,
    email_subject         VARCHAR(160),
    email_body            TEXT,
    tag                   VARCHAR(60),
    footer_note           VARCHAR(500),
    ttl_seconds           BIGINT,
    cursor_user_id        BIGINT,
    runner_token          VARCHAR(40),
    attempts              INTEGER       NOT NULL DEFAULT 0,
    updated_at            TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_admin_broadcasts_created ON admin_broadcasts (created_at);
CREATE UNIQUE INDEX IF NOT EXISTS ux_admin_broadcasts_request_key
    ON admin_broadcasts (request_key) WHERE request_key IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_admin_broadcasts_sending
    ON admin_broadcasts (updated_at) WHERE status = 'SENDING';

-- Bulk pushes (admin broadcasts) wait behind personal alerts such as
-- credits and debits. 0 = personal, 1 = bulk.
ALTER TABLE outbox_events ADD COLUMN IF NOT EXISTS priority SMALLINT NOT NULL DEFAULT 0;
