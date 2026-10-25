use std::time::{SystemTime, UNIX_EPOCH};
use truyencloud_core::epub::build_epub_bytes;
use truyencloud_core::fsrs::{FsrsRating, FsrsScheduler};
use truyencloud_core::hanviet::fold_vietnamese_ascii;
use truyencloud_core::models::{
    Chapter, DiscoverResponse, GlossaryMap, Novel, ReadingHistoryEntry, VocabularyCard,
};
use truyencloud_core::text_paging::paginate_chapter;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AppTab {
    Discover,
    DetailAndReader,
    VocabularyDeck,
    ReadingHistory,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum QuickFilter {
    All,
    Translated,
    Overnight,
    Completed,
}

pub struct TruyenCloudRustApp {
    pub active_tab: AppTab,
    pub server_url: String,
    pub status_banner: String,
    pub search_query: String,
    pub quick_filter: QuickFilter,
    pub selected_source: String,
    pub novels: Vec<Novel>,
    pub selected_novel: Option<Novel>,
    pub current_chapter: Chapter,
    pub pages: Vec<Vec<String>>,
    pub current_page_idx: usize,
    pub selected_voice: String,
    pub is_playing_audio: bool,
    pub glossary: GlossaryMap,
    pub new_term_zh: String,
    pub new_term_vi: String,
    pub vocab_deck: Vec<VocabularyCard>,
    pub current_card_idx: usize,
    pub card_flipped: bool,
    pub history: Vec<ReadingHistoryEntry>,
    pub fsrs: FsrsScheduler,
}

impl Default for TruyenCloudRustApp {
    fn default() -> Self {
        let sample_novels = sample_catalog();
        let first_novel = sample_novels.first().cloned();
        let sample_chap = sample_chapter("n_tien_nghich", 1);
        let pages = paginate_chapter(sample_chap.best_body(), 900, 16);

        let mut glossary = GlossaryMap::new();
        glossary.insert("王林".into(), "Vương Lâm".into());
        glossary.insert("铁柱".into(), "Thiết Trụ".into());
        glossary.insert("恒岳派".into(), "Hằng Nhạc Phái".into());

        Self {
            active_tab: AppTab::Discover,
            server_url: std::env::var("TRUYENCLOUD_API_URL")
                .unwrap_or_else(|_| "https://truyen.achatvn.com".to_string()),
            status_banner: "Sẵn sàng · TruyenCloud Rust Client v1.6.0".to_string(),
            search_query: String::new(),
            quick_filter: QuickFilter::All,
            selected_source: String::new(),
            novels: sample_novels,
            selected_novel: first_novel,
            current_chapter: sample_chap,
            pages,
            current_page_idx: 0,
            selected_voice: "NgocHuyen".to_string(),
            is_playing_audio: false,
            glossary,
            new_term_zh: String::new(),
            new_term_vi: String::new(),
            vocab_deck: sample_vocab_deck(),
            current_card_idx: 0,
            card_flipped: false,
            history: vec![ReadingHistoryEntry {
                novel_id: "n_tien_nghich".into(),
                novel_title: "Tiên Nghịch".into(),
                chapter_number: 1,
                chapter_title: "Chương 1: Ly biệt".into(),
                timestamp_ms: now_ms(),
                is_audio: false,
            }],
            fsrs: FsrsScheduler::default(),
        }
    }
}

impl TruyenCloudRustApp {
    pub fn fetch_discover_from_server(&mut self) {
        let url = format!(
            "{}/v1/novels/discover?sort=recent&limit=500",
            self.server_url.trim_end_matches('/')
        );
        match reqwest::blocking::get(&url) {
            Ok(resp) => match resp.json::<DiscoverResponse>() {
                Ok(data) if !data.novels.is_empty() => {
                    let count = data.novels.len();
                    self.novels = data.novels;
                    self.status_banner =
                        format!("Đã đồng bộ {} bộ truyện từ {}", count, self.server_url);
                }
                Ok(_) => {
                    self.status_banner =
                        "Máy chủ trả về danh sách trống, đang hiển thị bộ mẫu.".into();
                }
                Err(e) => {
                    self.status_banner = format!("Lỗi giải mã JSON từ máy chủ: {}", e);
                }
            },
            Err(e) => {
                self.status_banner = format!("Không kết nối được {}: {}", url, e);
            }
        }
    }

    pub fn filtered_novels(&self) -> Vec<Novel> {
        let q = fold_vietnamese_ascii(self.search_query.trim());
        self.novels
            .iter()
            .filter(|n| match self.quick_filter {
                QuickFilter::All => true,
                QuickFilter::Translated => n.translated_chapters > 0,
                QuickFilter::Overnight => n.overnight_enabled,
                QuickFilter::Completed => n.status.eq_ignore_ascii_case("completed"),
            })
            .filter(|n| {
                self.selected_source.is_empty()
                    || n.source_id.eq_ignore_ascii_case(&self.selected_source)
            })
            .filter(|n| {
                if q.is_empty() {
                    return true;
                }
                fold_vietnamese_ascii(n.display_title()).contains(&q)
                    || fold_vietnamese_ascii(&n.title).contains(&q)
                    || fold_vietnamese_ascii(n.display_author()).contains(&q)
            })
            .cloned()
            .collect()
    }

    pub fn open_novel(&mut self, novel: Novel, chapter_num: i32, is_audio: bool) {
        let chap = sample_chapter(&novel.id, chapter_num);
        self.pages = paginate_chapter(chap.best_body(), 900, 16);
        self.current_page_idx = 0;
        self.current_chapter = chap.clone();
        self.selected_novel = Some(novel.clone());
        self.is_playing_audio = is_audio;
        self.active_tab = AppTab::DetailAndReader;

        self.history.retain(|h| {
            !(h.novel_id == novel.id && h.chapter_number == chapter_num)
        });
        let display_title = novel.display_title().to_string();
        self.history.insert(
            0,
            ReadingHistoryEntry {
                novel_id: novel.id,
                novel_title: display_title,
                chapter_number: chapter_num,
                chapter_title: chap.display_title(),
                timestamp_ms: now_ms(),
                is_audio,
            },
        );
    }

    pub fn export_current_epub(&mut self) {
        let Some(novel) = self.selected_novel.clone() else {
            return;
        };
        let chaps = vec![self.current_chapter.clone()];
        match build_epub_bytes(&novel, &chaps) {
            Ok(bytes) => {
                let path = std::env::temp_dir().join(format!("{}.epub", novel.id));
                match std::fs::write(&path, &bytes) {
                    Ok(()) => {
                        self.status_banner = format!(
                            "Đã xuất sách điện tử EPUB ({} bytes) tại: {}",
                            bytes.len(),
                            path.display()
                        );
                    }
                    Err(e) => self.status_banner = format!("Lỗi lưu tệp EPUB: {}", e),
                }
            }
            Err(e) => self.status_banner = format!("Lỗi đóng gói EPUB: {}", e),
        }
    }

    pub fn grade_current_card(&mut self, rating: FsrsRating) {
        if self.vocab_deck.is_empty() {
            return;
        }
        let idx = self.current_card_idx % self.vocab_deck.len();
        let updated = self.fsrs.review(&self.vocab_deck[idx], rating, now_ms());
        self.status_banner = format!(
            "Đã chấm '{}' ({}) → Độ bền S = {:.2} ngày",
            updated.word,
            rating.label_vi(),
            updated.stability
        );
        self.vocab_deck[idx] = updated;
        self.card_flipped = false;
        self.current_card_idx = (idx + 1) % self.vocab_deck.len();
    }
}

pub fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

fn sample_catalog() -> Vec<Novel> {
    vec![
        Novel {
            id: "n_tien_nghich".into(),
            title: "仙逆".into(),
            title_vi: Some("Tiên Nghịch".into()),
            author: "Nhĩ Căn".into(),
            source_id: "69shuba".into(),
            status: "completed".into(),
            total_chapters: 2088,
            translated_chapters: 120,
            overnight_enabled: true,
            description: "Thuận vi phàm, nghịch tắc tiên — chỉ ở trong một niệm của Vương Lâm.".into(),
            ..Default::default()
        },
        Novel {
            id: "n_quy_bi".into(),
            title: "诡秘之主".into(),
            title_vi: Some("Quỷ Bí Chi Chủ".into()),
            author: "Ái Tiềm Thủy Đích Ô Tặc".into(),
            source_id: "69shuba".into(),
            status: "completed".into(),
            total_chapters: 1432,
            translated_chapters: 85,
            overnight_enabled: true,
            description: "Hơi nước và máy móc dâng trào, ai có thể chạm tới phi phàm?".into(),
            ..Default::default()
        },
        Novel {
            id: "n_kiem_lai".into(),
            title: "剑来".into(),
            title_vi: Some("Kiếm Lai".into()),
            author: "Phong Hỏa Hí Chư Hầu".into(),
            source_id: "piaotia".into(),
            status: "ongoing".into(),
            total_chapters: 1210,
            translated_chapters: 42,
            overnight_enabled: false,
            description: "Thế giới rộng lớn không thiếu điều kỳ lạ, Trần Bình An một kiếm mở đường.".into(),
            ..Default::default()
        },
    ]
}

fn sample_chapter(novel_id: &str, num: i32) -> Chapter {
    Chapter {
        novel_id: novel_id.into(),
        chapter_number: num,
        title: format!("第{}章 离别", num),
        title_vi: Some(format!("Chương {}: Ly biệt", num)),
        content_raw: "铁柱坐在村口的老树下，望着远处的恒岳山脉。".into(),
        content_translated: Some(
            "Thiết Trụ ngồi dưới gốc cây già đầu làng, phóng tầm mắt nhìn dãy núi Hằng Nhạc xa xăm.\n\n\
             Gió chiều thổi qua tán lá xào xạc. Cha vỗ nhẹ lên vai hắn, dặn dò khi lên Hằng Nhạc Phái phải chăm chỉ tu hành.\n\n\
             Vương Lâm nắm chặt bùa hộ mệnh trong tay, bước chân đầu tiên vào con đường tu tiên đầy sóng gió."
                .into(),
        ),
        content_english: Some(
            "Tie Zhu sat beneath the ancient tree at the village entrance, gazing toward the distant Hengyue mountain range."
                .into(),
        ),
        english_level: Some("B2".into()),
        is_translated: true,
        ..Default::default()
    }
}

fn sample_vocab_deck() -> Vec<VocabularyCard> {
    vec![
        VocabularyCard {
            id: "vc_1".into(),
            word: "immortal".into(),
            phonetic: "/ɪˈmɔː.təl/ · 仙 (tiên)".into(),
            meaning_vi: "bất tử, tiên nhân".into(),
            example_sentence: "To defy the heavens is to become an immortal.".into(),
            example_translated: "Nghịch thiên chính là con đường hóa tiên.".into(),
            novel_id: "n_tien_nghich".into(),
            chapter_number: 1,
            stability: 3.1,
            difficulty: 4.8,
            reps: 2,
            ..Default::default()
        },
        VocabularyCard {
            id: "vc_2".into(),
            word: "cultivation".into(),
            phonetic: "/ˌkʌl.tɪˈveɪ.ʃən/ · 修仙 (tu tiên)".into(),
            meaning_vi: "sự tu luyện, trau dồi".into(),
            example_sentence: "Wang Lin stepped onto the path of cultivation.".into(),
            example_translated: "Vương Lâm bước lên con đường tu luyện.".into(),
            novel_id: "n_tien_nghich".into(),
            chapter_number: 1,
            stability: 5.4,
            difficulty: 4.2,
            reps: 3,
            ..Default::default()
        },
    ]
}
