-- ==============================================================================
-- TruyenCloud — Migration 02: Sổ từ vựng & nhật ký ôn tập trên cloud
-- ==============================================================================
--
-- VÌ SAO CẦN:
-- Trước migration này, toàn bộ sổ từ vựng chỉ nằm trong Room DB cục bộ trên máy.
-- Gỡ ứng dụng, đổi máy, hay chuyển từ Android sang iPhone là MẤT TRẮNG toàn bộ
-- tiến độ học. Với một ứng dụng học tập nơi người dùng đầu tư hàng trăm giờ,
-- đây là rủi ro tồn vong chứ không phải một tính năng "nice to have".
--
-- Chạy sau 01_schema.sql.
-- ==============================================================================

-- ------------------------------------------------------------------------------
-- 1. SỔ TỪ VỰNG
-- ------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.user_vocabulary (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,

    -- Nội dung từ điển
    word TEXT NOT NULL,
    -- Dạng nguyên thể: "ran"/"running"/"runs" đều quy về "run" nên không sinh 3 thẻ rời rạc
    lemma TEXT NOT NULL DEFAULT '',
    -- Phân biệt nghĩa của cùng một chữ: spring (mùa xuân) vs spring (nhảy bật)
    sense_key TEXT NOT NULL DEFAULT '',
    ipa_us TEXT DEFAULT '',
    ipa_uk TEXT DEFAULT '',
    part_of_speech TEXT DEFAULT '',
    cefr_level TEXT DEFAULT '',
    context_meaning_vi TEXT DEFAULT '',
    english_definition TEXT DEFAULT '',
    etymology TEXT DEFAULT '',
    collocations JSONB DEFAULT '[]'::jsonb,
    ai_example_sentences JSONB DEFAULT '[]'::jsonb,

    -- NGỮ CẢNH GỐC TRONG TIỂU THUYẾT
    -- Đây là tài sản khác biệt của TruyenCloud: câu THẬT nơi người học gặp từ.
    -- Anki, Quizlet hay MochiMochi không thể có dữ liệu này.
    novel_id UUID REFERENCES public.novels(id) ON DELETE SET NULL,
    book_title TEXT DEFAULT '',
    chapter_number INT DEFAULT 0,
    context_sentence TEXT DEFAULT '',
    context_char_offset INT DEFAULT -1,

    -- Trạng thái trí nhớ theo FSRS
    stability DOUBLE PRECISION NOT NULL DEFAULT 0,      -- số ngày để xác suất nhớ rơi về 90%
    difficulty DOUBLE PRECISION NOT NULL DEFAULT 0,     -- độ khó nội tại, thang 1..10
    fsrs_state SMALLINT NOT NULL DEFAULT 0,             -- 0 Mới, 1 Đang học, 2 Ôn tập, 3 Học lại
    reps INT NOT NULL DEFAULT 0,
    lapses INT NOT NULL DEFAULT 0,
    mastered_count INT NOT NULL DEFAULT 0,
    last_reviewed_at TIMESTAMPTZ,
    due_at TIMESTAMPTZ,
    is_suspended BOOLEAN NOT NULL DEFAULT FALSE,

    -- Nội dung học bổ sung
    life_scenarios JSONB,
    user_sentences JSONB DEFAULT '[]'::jsonb,
    flashcard_art_url TEXT DEFAULT '',
    visual_mnemonic TEXT DEFAULT '',
    art_palette_hex TEXT DEFAULT '#D488FF',

    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- Một người học chỉ có một thẻ cho mỗi (nguyên thể, nghĩa)
    UNIQUE (user_id, lemma, sense_key)
);

-- Truy vấn nóng nhất: "hôm nay tôi cần ôn những thẻ nào"
CREATE INDEX IF NOT EXISTS idx_vocab_user_due
    ON public.user_vocabulary (user_id, due_at)
    WHERE is_suspended = FALSE;

-- "Ôn trước khi đọc chương mới": tìm từ đã lưu theo truyện/chương
CREATE INDEX IF NOT EXISTS idx_vocab_user_novel
    ON public.user_vocabulary (user_id, novel_id, chapter_number);

CREATE INDEX IF NOT EXISTS idx_vocab_user_word
    ON public.user_vocabulary (user_id, word);

-- Đồng bộ tăng dần: chỉ kéo về những gì đổi từ lần sync trước
CREATE INDEX IF NOT EXISTS idx_vocab_user_updated
    ON public.user_vocabulary (user_id, updated_at DESC);

ALTER TABLE public.user_vocabulary ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Users manage own vocabulary" ON public.user_vocabulary;
CREATE POLICY "Users manage own vocabulary"
    ON public.user_vocabulary FOR ALL
    USING (auth.uid() = user_id)
    WITH CHECK (auth.uid() = user_id);


-- ------------------------------------------------------------------------------
-- 2. NHẬT KÝ ÔN TẬP
-- ------------------------------------------------------------------------------
-- Không có bảng này thì: không tính được tỉ lệ nhớ thật, không vẽ được biểu đồ
-- tiến bộ, không tính được chuỗi ngày học, và quan trọng nhất — sau này KHÔNG THỂ
-- hiệu chỉnh trọng số FSRS cho riêng người học vì không có dữ liệu lịch sử.
-- Phải ghi từ bây giờ để sáu tháng nữa đã có đủ dữ liệu.
CREATE TABLE IF NOT EXISTS public.user_review_logs (
    id BIGSERIAL PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    vocabulary_id UUID REFERENCES public.user_vocabulary(id) ON DELETE CASCADE,
    word TEXT DEFAULT '',

    reviewed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    date_key TEXT NOT NULL DEFAULT '',            -- YYYY-MM-DD theo giờ địa phương của người học
    rating SMALLINT NOT NULL,                     -- 1 Quên, 2 Khó, 3 Tốt, 4 Dễ
    review_mode TEXT NOT NULL DEFAULT 'recognition',  -- recognition | cloze | typing | listening | quiz
    response_ms INT DEFAULT 0,                    -- dự báo độ nhớ tốt hơn cả điểm tự chấm

    elapsed_days DOUBLE PRECISION DEFAULT 0,
    scheduled_days DOUBLE PRECISION DEFAULT 0,
    stability_before DOUBLE PRECISION DEFAULT 0,
    stability_after DOUBLE PRECISION DEFAULT 0,
    difficulty_before DOUBLE PRECISION DEFAULT 0,
    difficulty_after DOUBLE PRECISION DEFAULT 0,
    retrievability_before DOUBLE PRECISION DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_review_logs_user_date
    ON public.user_review_logs (user_id, date_key DESC);

CREATE INDEX IF NOT EXISTS idx_review_logs_user_time
    ON public.user_review_logs (user_id, reviewed_at DESC);

CREATE INDEX IF NOT EXISTS idx_review_logs_vocab
    ON public.user_review_logs (vocabulary_id);

ALTER TABLE public.user_review_logs ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Users manage own review logs" ON public.user_review_logs;
CREATE POLICY "Users manage own review logs"
    ON public.user_review_logs FOR ALL
    USING (auth.uid() = user_id)
    WITH CHECK (auth.uid() = user_id);


-- ------------------------------------------------------------------------------
-- 3. CẤU HÌNH PHIÊN HỌC
-- ------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.user_study_config (
    user_id UUID PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
    -- Không giới hạn thẻ mới là cách nhanh nhất để chôn vùi người học
    new_cards_per_day INT NOT NULL DEFAULT 12,
    max_reviews_per_day INT NOT NULL DEFAULT 80,
    -- Xác suất nhớ mong muốn: cao hơn = ôn dày hơn
    desired_retention DOUBLE PRECISION NOT NULL DEFAULT 0.90,
    preferred_english_level TEXT NOT NULL DEFAULT 'c1',
    -- Nhắc ôn hằng ngày, HH:MM theo giờ địa phương
    reminder_time TEXT DEFAULT '20:00',
    reminder_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE public.user_study_config ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Users manage own study config" ON public.user_study_config;
CREATE POLICY "Users manage own study config"
    ON public.user_study_config FOR ALL
    USING (auth.uid() = user_id)
    WITH CHECK (auth.uid() = user_id);


-- ------------------------------------------------------------------------------
-- 4. TIẾN ĐỘ ĐỌC (thiếu trong 01_schema.sql dù backend vẫn gọi tới)
-- ------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.reading_progress (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    novel_id TEXT NOT NULL,
    current_chapter INT NOT NULL DEFAULT 0,
    scroll_position DOUBLE PRECISION NOT NULL DEFAULT 0,
    shelf_name TEXT NOT NULL DEFAULT 'reading',
    is_favorite BOOLEAN NOT NULL DEFAULT FALSE,
    last_read_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Ràng buộc này chính là thứ ngăn việc sinh một dòng mới sau mỗi lần đồng bộ
    UNIQUE (user_id, novel_id)
);

CREATE INDEX IF NOT EXISTS idx_reading_progress_user
    ON public.reading_progress (user_id, last_read_at DESC);

ALTER TABLE public.reading_progress ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Users manage own reading progress" ON public.reading_progress;
CREATE POLICY "Users manage own reading progress"
    ON public.reading_progress FOR ALL
    USING (auth.uid() = user_id)
    WITH CHECK (auth.uid() = user_id);


-- ------------------------------------------------------------------------------
-- 5. BẢN DỊCH TIẾNG ANH THEO CẤP ĐỘ (dùng chung cho mọi người học)
-- ------------------------------------------------------------------------------
-- Backend đang lưu các trường `translated_content_en_{level}` trên đĩa nhưng
-- schema chưa có chỗ chứa. Tách thành bảng riêng để một chương có nhiều cấp độ.
CREATE TABLE IF NOT EXISTS public.chapter_translations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    novel_id UUID NOT NULL REFERENCES public.novels(id) ON DELETE CASCADE,
    chapter_number INT NOT NULL,
    target_language TEXT NOT NULL,              -- 'en' | 'bilingual'
    cefr_level TEXT NOT NULL DEFAULT 'c1',      -- 'b1' | 'b2' | 'c1' | 'c2'
    content TEXT NOT NULL,
    engine TEXT DEFAULT 'ANTIGRAVITY',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (novel_id, chapter_number, target_language, cefr_level)
);

CREATE INDEX IF NOT EXISTS idx_chapter_tr_lookup
    ON public.chapter_translations (novel_id, chapter_number, target_language, cefr_level);

ALTER TABLE public.chapter_translations ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Public read chapter translations" ON public.chapter_translations;
CREATE POLICY "Public read chapter translations"
    ON public.chapter_translations FOR SELECT
    USING (true);


-- ------------------------------------------------------------------------------
-- 6. TỰ ĐỘNG CẬP NHẬT updated_at
-- ------------------------------------------------------------------------------
-- Đồng bộ last-write-wins phụ thuộc vào mốc này nên không thể để client tự đặt.
CREATE OR REPLACE FUNCTION public.touch_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_vocab_touch ON public.user_vocabulary;
CREATE TRIGGER trg_vocab_touch
    BEFORE UPDATE ON public.user_vocabulary
    FOR EACH ROW EXECUTE FUNCTION public.touch_updated_at();

DROP TRIGGER IF EXISTS trg_study_config_touch ON public.user_study_config;
CREATE TRIGGER trg_study_config_touch
    BEFORE UPDATE ON public.user_study_config
    FOR EACH ROW EXECUTE FUNCTION public.touch_updated_at();


-- ------------------------------------------------------------------------------
-- 7. ĐỒNG BỘ THỜI GIAN THỰC GIỮA CÁC THIẾT BỊ
-- ------------------------------------------------------------------------------
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_publication_tables
        WHERE pubname = 'supabase_realtime' AND tablename = 'user_vocabulary'
    ) THEN
        ALTER PUBLICATION supabase_realtime ADD TABLE public.user_vocabulary;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_publication_tables
        WHERE pubname = 'supabase_realtime' AND tablename = 'reading_progress'
    ) THEN
        ALTER PUBLICATION supabase_realtime ADD TABLE public.reading_progress;
    END IF;
END $$;


-- ------------------------------------------------------------------------------
-- 8. THỐNG KÊ HỌC TẬP
-- ------------------------------------------------------------------------------
-- Thay cho các con số cứng trong ứng dụng (streak = 7, 48.5 giờ đọc, 158 chương...).
CREATE OR REPLACE VIEW public.user_study_stats AS
SELECT
    v.user_id,
    COUNT(*)                                              AS total_words,
    COUNT(*) FILTER (WHERE v.stability >= 21)             AS mature_words,
    COUNT(*) FILTER (WHERE v.fsrs_state = 0)              AS new_words,
    COUNT(*) FILTER (WHERE v.lapses >= 8)                 AS leech_words,
    COUNT(*) FILTER (WHERE v.due_at <= now() AND NOT v.is_suspended) AS due_now,
    COALESCE(AVG(v.stability), 0)                         AS avg_stability
FROM public.user_vocabulary v
GROUP BY v.user_id;
