-- A still picture (the first frame of a GIF) for Android, which does not
-- animate GIFs in notifications. iPhone and the in-app list keep the GIF.
ALTER TABLE admin_broadcasts ADD COLUMN IF NOT EXISTS still_image_url VARCHAR(1000);
ALTER TABLE notifications ADD COLUMN IF NOT EXISTS still_image_url VARCHAR(1000);
