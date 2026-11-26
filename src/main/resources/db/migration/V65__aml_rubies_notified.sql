-- When the team told Rubies about an escalated payment review.
ALTER TABLE aml_holds ADD COLUMN IF NOT EXISTS rubies_notified_at TIMESTAMP;
