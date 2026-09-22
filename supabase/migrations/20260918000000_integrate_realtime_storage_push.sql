-- Spring writes directly to PostgreSQL, while browsers keep using Supabase Realtime.
-- Explicit publication membership makes notice inserts and GPS latest-state updates
-- observable regardless of whether the schema was created by the CLI or dashboard.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_publication WHERE pubname = 'supabase_realtime') THEN
    IF NOT EXISTS (
      SELECT 1 FROM pg_publication_tables
      WHERE pubname = 'supabase_realtime'
        AND schemaname = 'public'
        AND tablename = 'notices'
    ) THEN
      ALTER PUBLICATION supabase_realtime ADD TABLE public.notices;
    END IF;

    IF NOT EXISTS (
      SELECT 1 FROM pg_publication_tables
      WHERE pubname = 'supabase_realtime'
        AND schemaname = 'public'
        AND tablename = 'bus_latest_state'
    ) THEN
      ALTER PUBLICATION supabase_realtime ADD TABLE public.bus_latest_state;
    END IF;
  END IF;
END $$;

-- Keep the Storage boundary aligned with the Spring multipart validation.
INSERT INTO storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
VALUES (
  'notice-images',
  'notice-images',
  TRUE,
  5242880,
  ARRAY['image/jpeg', 'image/png', 'image/webp', 'image/gif']
)
ON CONFLICT (id) DO UPDATE SET
  public = EXCLUDED.public,
  file_size_limit = EXCLUDED.file_size_limit,
  allowed_mime_types = EXCLUDED.allowed_mime_types;
