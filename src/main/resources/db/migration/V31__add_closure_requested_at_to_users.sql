-- Records when a user tapped "Delete account" but a withdrawal still had to
-- happen before the account could actually close. Their intent is captured (and
-- PIN-verified) at that moment, so AccountClosureFinalizerJob uses this
-- timestamp to finish the closure on its own once the wallet empties — a user
-- who withdraws and never taps Delete a second time must not be left with
-- dissolved budgets, broken savings and a still-open account.
--
-- NULL = not pending. Cleared on cancellation and on actual closure.
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS closure_requested_at TIMESTAMP;
