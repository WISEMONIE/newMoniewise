-- Last Lagos day an along-the-way preparation reminder was sent.
-- The 4-hour job uses it so those reminders repeat on an interval
-- instead of firing once, seven days before the event.
ALTER TABLE savings_goals
    ADD COLUMN IF NOT EXISTS last_prep_reminder_date DATE;

-- Existing open events have no last-sent day. Leaving it empty would make
-- the next job treat almost all of them as due at once. Stamp today in
-- Lagos so the cadence starts from this deploy instead of bursting.
UPDATE savings_goals
SET last_prep_reminder_date = (CURRENT_TIMESTAMP AT TIME ZONE 'Africa/Lagos')::date
WHERE last_prep_reminder_date IS NULL
  AND status = 'ACTIVE';
