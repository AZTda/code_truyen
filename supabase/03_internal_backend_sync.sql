-- ==============================================================================
-- TruyenCloud — Internal backend sync contract
-- ==============================================================================
--
-- The current backend authenticates against server/data/users.json and issues
-- signed IDs such as `user_test1`. The tables in 02_vocabulary.sql are for a
-- future Supabase Auth migration and use UUID foreign keys, so they cannot be
-- written by the current backend. These tables make the current production
-- contract explicit instead of relying on implicit SQLite-only tables.
--
-- Run after 01_schema.sql and 02_vocabulary.sql when SUPABASE_* is enabled.
-- ===============================================================================

CREATE TABLE IF NOT EXISTS public.reading_progress_backend (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    novel_id TEXT NOT NULL,
    current_chapter INT NOT NULL DEFAULT 0,
    scroll_position DOUBLE PRECISION NOT NULL DEFAULT 0,
    shelf_name TEXT NOT NULL DEFAULT 'reading',
    is_favorite BOOLEAN NOT NULL DEFAULT FALSE,
    last_read_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    synced_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(user_id, novel_id)
);

CREATE INDEX IF NOT EXISTS idx_backend_progress_user
    ON public.reading_progress_backend(user_id, last_read_at DESC);

CREATE TABLE IF NOT EXISTS public.bookmarks (
    id TEXT PRIMARY KEY,
    client_id TEXT NOT NULL DEFAULT '',
    user_id TEXT NOT NULL,
    novel_id TEXT NOT NULL,
    chapter_number INT NOT NULL,
    chapter_title TEXT NOT NULL DEFAULT '',
    paragraph_index INT NOT NULL DEFAULT 0,
    selected_text TEXT NOT NULL DEFAULT '',
    note TEXT NOT NULL DEFAULT '',
    color_hex TEXT NOT NULL DEFAULT '#FFD54F',
    created_at BIGINT NOT NULL DEFAULT 0,
    synced_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_backend_bookmarks_user
    ON public.bookmarks(user_id, novel_id, chapter_number);

CREATE TABLE IF NOT EXISTS public.saved_words (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    word_id TEXT NOT NULL,
    word TEXT NOT NULL DEFAULT '',
    data JSONB NOT NULL DEFAULT '{}'::jsonb,
    updated_at BIGINT NOT NULL DEFAULT 0,
    synced_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(user_id, word_id)
);

CREATE INDEX IF NOT EXISTS idx_backend_saved_words_user
    ON public.saved_words(user_id, updated_at DESC);

CREATE TABLE IF NOT EXISTS public.review_logs (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    data JSONB NOT NULL DEFAULT '{}'::jsonb,
    reviewed_at BIGINT NOT NULL DEFAULT 0,
    synced_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_backend_review_logs_user
    ON public.review_logs(user_id, reviewed_at DESC);

COMMENT ON TABLE public.reading_progress_backend IS
    'Sync contract for the current signed internal-auth backend.';
