-- Personal referral codes and one setup-fee credit per funded friend.
ALTER TABLE users ADD COLUMN referral_code VARCHAR(16);
ALTER TABLE users ADD COLUMN referred_by_user_id BIGINT;

CREATE UNIQUE INDEX uq_users_referral_code ON users (referral_code);

ALTER TABLE users
    ADD CONSTRAINT fk_users_referred_by
        FOREIGN KEY (referred_by_user_id) REFERENCES users (id);

CREATE TABLE referral_rewards (
    id               BIGSERIAL PRIMARY KEY,
    referrer_user_id BIGINT       NOT NULL REFERENCES users (id),
    referred_user_id BIGINT       NOT NULL UNIQUE REFERENCES users (id),
    status           VARCHAR(16)  NOT NULL,
    created_at       TIMESTAMP    NOT NULL,
    earned_at        TIMESTAMP,
    used_at          TIMESTAMP,
    used_budget_id   BIGINT,
    amount_waived    NUMERIC(19, 2)
);

CREATE INDEX idx_referral_rewards_referrer_status
    ON referral_rewards (referrer_user_id, status);
