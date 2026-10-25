use serde::{Deserialize, Serialize};
use std::collections::HashMap;

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct Novel {
    pub id: String,
    #[serde(default)]
    pub title: String,
    #[serde(default)]
    pub title_vi: Option<String>,
    #[serde(default)]
    pub author: String,
    #[serde(default)]
    pub author_vi: Option<String>,
    #[serde(default)]
    pub cover_url: String,
    #[serde(default)]
    pub description: String,
    #[serde(default)]
    pub description_vi: Option<String>,
    #[serde(default)]
    pub source_id: String,
    #[serde(default)]
    pub source_url: String,
    #[serde(default)]
    pub status: String,
    #[serde(default)]
    pub total_chapters: i32,
    #[serde(default)]
    pub translated_chapters: i32,
    #[serde(default)]
    pub overnight_enabled: bool,
    #[serde(default)]
    pub preferred_voice: Option<String>,
    #[serde(default)]
    pub reason: Option<String>,
}

impl Novel {
    pub fn display_title(&self) -> &str {
        match self.title_vi.as_deref() {
            Some(vi) if !vi.trim().is_empty() => vi.trim(),
            _ => self.title.as_str(),
        }
    }

    pub fn display_author(&self) -> &str {
        match self.author_vi.as_deref() {
            Some(vi) if !vi.trim().is_empty() => vi.trim(),
            _ if !self.author.trim().is_empty() => self.author.as_str(),
            _ => "Khuyết danh",
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct Chapter {
    pub novel_id: String,
    pub chapter_number: i32,
    #[serde(default)]
    pub title: String,
    #[serde(default)]
    pub title_vi: Option<String>,
    #[serde(default)]
    pub content_raw: String,
    #[serde(default)]
    pub content_translated: Option<String>,
    #[serde(default)]
    pub content_english: Option<String>,
    #[serde(default)]
    pub english_level: Option<String>,
    #[serde(default)]
    pub source_url: String,
    #[serde(default)]
    pub is_translated: bool,
}

impl Chapter {
    pub fn display_title(&self) -> String {
        if let Some(vi) = self.title_vi.as_deref() {
            if !vi.trim().is_empty() {
                return vi.trim().to_string();
            }
        }
        if !self.title.trim().is_empty() {
            return self.title.trim().to_string();
        }
        format!("Chương {}", self.chapter_number)
    }

    pub fn best_body(&self) -> &str {
        if let Some(vi) = self.content_translated.as_deref() {
            if !vi.trim().is_empty() {
                return vi;
            }
        }
        self.content_raw.as_str()
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct DiscoverResponse {
    pub novels: Vec<Novel>,
    pub total: usize,
    #[serde(default)]
    pub personalized: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct UserProfile {
    pub id: String,
    pub email: String,
    pub display_name: String,
    #[serde(default)]
    pub bio: String,
    #[serde(default)]
    pub vip_tier: String,
    #[serde(default)]
    pub avatar_url: String,
    #[serde(default)]
    pub banner_url: String,
    #[serde(default = "default_provider")]
    pub auth_provider: String,
    #[serde(default = "default_true")]
    pub is_logged_in: bool,
    #[serde(default)]
    pub is_admin: bool,
}

fn default_provider() -> String {
    "email".to_string()
}

fn default_true() -> bool {
    true
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AuthResponse {
    pub status: String,
    pub message: String,
    pub token: String,
    pub user: UserProfile,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct VocabularyCard {
    pub id: String,
    pub word: String,
    #[serde(default)]
    pub phonetic: String,
    #[serde(default)]
    pub meaning_vi: String,
    #[serde(default)]
    pub example_sentence: String,
    #[serde(default)]
    pub example_translated: String,
    #[serde(default)]
    pub novel_id: String,
    #[serde(default)]
    pub chapter_number: i32,
    #[serde(default)]
    pub stability: f64,
    #[serde(default)]
    pub difficulty: f64,
    #[serde(default)]
    pub reps: u32,
    #[serde(default)]
    pub lapses: u32,
    #[serde(default)]
    pub next_review_ms: i64,
    #[serde(default)]
    pub last_review_ms: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct ReadingHistoryEntry {
    pub novel_id: String,
    pub novel_title: String,
    pub chapter_number: i32,
    pub chapter_title: String,
    pub timestamp_ms: i64,
    pub is_audio: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct VoiceProfileInfo {
    pub id: String,
    pub name: String,
    pub region: String,
    pub gender: String,
}

pub type GlossaryMap = HashMap<String, String>;
