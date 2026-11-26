-- Seed the "What's New" modal content so it can be updated from the admin panel
-- without a code deploy. The items column is a JSON array of {title, body} objects.
INSERT INTO system_config (config_key, config_value, description)
VALUES
  ('app.whats_new.title', 'What''s new in Wisemonie', 'Headline for the What''s New modal shown after app update')
ON CONFLICT (config_key) DO NOTHING;

INSERT INTO system_config (config_key, config_value, description)
VALUES
  ('app.whats_new.items', '[{"title":"Budget Templates","body":"save your envelope setup and reuse it anytime."},{"title":"Auto Envelope Transfer","body":"automatically send released envelope money to your chosen bank account."},{"title":"Stability improvements","body":"budgets, envelopes, and transfers are smoother now."}]', 'JSON array of {title, body} items for the What''s New modal')
ON CONFLICT (config_key) DO NOTHING;
