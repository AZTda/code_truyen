-- ==============================================================================
-- TruyenCloud 2026 — Supabase PostgreSQL Schema
-- Thin Client + Fat Backend Architecture
-- ==============================================================================

-- 1. EXTENSIONS
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- 2. USER PROFILES (Linked to Supabase auth.users)
CREATE TABLE IF NOT EXISTS public.user_profiles (
    id UUID PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
    display_name TEXT,
    avatar_url TEXT,
    preferred_theme TEXT DEFAULT 'system', -- 'dark' | 'light' | 'system'
    reader_font_size INT DEFAULT 18,
    reader_line_height FLOAT DEFAULT 1.8,
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ DEFAULT now()
);

ALTER TABLE public.user_profiles ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Users can view own profile"
    ON public.user_profiles FOR SELECT
    USING (auth.uid() = id);

CREATE POLICY "Users can update own profile"
    ON public.user_profiles FOR UPDATE
    USING (auth.uid() = id);

CREATE POLICY "Users can insert own profile"
    ON public.user_profiles FOR INSERT
    WITH CHECK (auth.uid() = id);

-- 3. NOVELS (Global catalog across all users)
CREATE TABLE IF NOT EXISTS public.novels (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_url TEXT UNIQUE NOT NULL,
    source_id TEXT NOT NULL, -- '69shuba' | 'piaotia' | 'uukanshu' | 'jjwxc'
    title TEXT NOT NULL,
    title_vi TEXT DEFAULT '',
    author TEXT DEFAULT '',
    cover_url TEXT DEFAULT '',
    genre TEXT[] DEFAULT '{}',
    status TEXT DEFAULT 'ongoing', -- 'ongoing' | 'completed' | 'hiatus'
    total_chapters INT DEFAULT 0,
    description TEXT DEFAULT '',
    last_scraped_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_novels_source_url ON public.novels(source_url);
CREATE INDEX IF NOT EXISTS idx_novels_source_id ON public.novels(source_id);
CREATE INDEX IF NOT EXISTS idx_novels_title ON public.novels(title);

ALTER TABLE public.novels ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Public read novels"
    ON public.novels FOR SELECT
    USING (true);

-- 4. CHAPTERS (Global chapters with cached original and translated content)
CREATE TABLE IF NOT EXISTS public.chapters (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    novel_id UUID NOT NULL REFERENCES public.novels(id) ON DELETE CASCADE,
    chapter_number INT NOT NULL,
    title TEXT DEFAULT '',
    url TEXT DEFAULT '',
    original_content TEXT,
    translated_content TEXT,
    content_hash TEXT,
    word_count INT DEFAULT 0,
    translation_engine TEXT DEFAULT 'ANTIGRAVITY',
    translated_at TIMESTAMPTZ,
    scraped_at TIMESTAMPTZ DEFAULT now(),
    UNIQUE(novel_id, chapter_number)
);

CREATE INDEX IF NOT EXISTS idx_chapters_novel_chapter ON public.chapters(novel_id, chapter_number);
CREATE INDEX IF NOT EXISTS idx_chapters_novel_id ON public.chapters(novel_id);

ALTER TABLE public.chapters ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Public read chapters"
    ON public.chapters FOR SELECT
    USING (true);

-- 5. USER BOOKSHELVES (Reading progress, per user)
CREATE TABLE IF NOT EXISTS public.user_bookshelves (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    novel_id UUID NOT NULL REFERENCES public.novels(id) ON DELETE CASCADE,
    current_chapter INT DEFAULT 0,
    current_scroll_position FLOAT DEFAULT 0.0,
    is_favorite BOOLEAN DEFAULT FALSE,
    shelf_name TEXT DEFAULT 'reading', -- 'reading' | 'completed' | 'planned'
    last_read_at TIMESTAMPTZ DEFAULT now(),
    added_at TIMESTAMPTZ DEFAULT now(),
    UNIQUE(user_id, novel_id)
);

CREATE INDEX IF NOT EXISTS idx_bookshelves_user ON public.user_bookshelves(user_id);
CREATE INDEX IF NOT EXISTS idx_bookshelves_user_novel ON public.user_bookshelves(user_id, novel_id);

ALTER TABLE public.user_bookshelves ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Users manage own bookshelf"
    ON public.user_bookshelves FOR ALL
    USING (auth.uid() = user_id)
    WITH CHECK (auth.uid() = user_id);

-- 6. USER NOTES & HIGHLIGHTS
CREATE TABLE IF NOT EXISTS public.user_notes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    novel_id UUID NOT NULL REFERENCES public.novels(id) ON DELETE CASCADE,
    chapter_number INT,
    content TEXT NOT NULL,
    highlighted_text TEXT,
    created_at TIMESTAMPTZ DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_notes_user_novel ON public.user_notes(user_id, novel_id);

ALTER TABLE public.user_notes ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Users manage own notes"
    ON public.user_notes FOR ALL
    USING (auth.uid() = user_id)
    WITH CHECK (auth.uid() = user_id);

-- 7. USER TERM MEMORIES (Personalized glossary per novel)
CREATE TABLE IF NOT EXISTS public.user_term_memories (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    novel_id UUID NOT NULL REFERENCES public.novels(id) ON DELETE CASCADE,
    source_term TEXT NOT NULL,
    target_term TEXT NOT NULL,
    created_at TIMESTAMPTZ DEFAULT now(),
    UNIQUE(user_id, novel_id, source_term)
);

CREATE INDEX IF NOT EXISTS idx_terms_user_novel ON public.user_term_memories(user_id, novel_id);

ALTER TABLE public.user_term_memories ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Users manage own terms"
    ON public.user_term_memories FOR ALL
    USING (auth.uid() = user_id)
    WITH CHECK (auth.uid() = user_id);

-- 8. REALTIME REPLICATION (For syncing reading progress across devices)
ALTER PUBLICATION supabase_realtime ADD TABLE public.user_bookshelves;
