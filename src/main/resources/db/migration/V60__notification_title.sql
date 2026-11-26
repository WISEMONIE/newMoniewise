-- Keep the lock-screen title so a later redelivery does not fall back
-- to the generic "Wisemonie Notification" line.
ALTER TABLE notifications
    ADD COLUMN IF NOT EXISTS title VARCHAR(255);
