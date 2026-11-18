ALTER TABLE live_guests
  ADD COLUMN IF NOT EXISTS last_polled timestamptz;
