use rusqlite::{params, Connection};
use serde_json::{json, Value};
use std::collections::{HashMap, HashSet};
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use truyencloud_core::crypto::{hash_password, verify_token, TokenPayload};
use truyencloud_core::models::{Chapter, Novel};

#[derive(Clone)]
pub struct AppState {
    pub data_dir: PathBuf,
    pub static_dir: PathBuf,
    pub db_path: PathBuf,
    pub session_secret: String,
    pub db_lock: Arc<Mutex<()>>,
    pub http_client: reqwest::Client,
    pub qwen_url: String,
}

impl AppState {
    pub fn new(server_root: &Path) -> Self {
        let data_dir = std::env::var("TRUYENCLOUD_DATA_DIR")
            .map(PathBuf::from)
            .unwrap_or_else(|_| server_root.join("data"));
        let static_dir = std::env::var("TRUYENCLOUD_STATIC_DIR")
            .map(PathBuf::from)
            .unwrap_or_else(|_| server_root.join("static"));
        let _ = fs::create_dir_all(&data_dir);
        let _ = fs::create_dir_all(&static_dir);

        let db_path = std::env::var("TRUYENCLOUD_DB_PATH")
            .map(PathBuf::from)
            .unwrap_or_else(|_| {
                let candidate = data_dir.join("db").join("truyencloud.db");
                if candidate.exists() { candidate } else { data_dir.join("truyencloud.db") }
            });

        let session_secret = load_or_create_secret(&data_dir.join(".session_secret"));
        let qwen_url = std::env::var("QWEN_API_URL")
            .or_else(|_| std::env::var("TRUYENCLOUD_QWEN_URL"))
            .unwrap_or_else(|_| "http://127.0.0.1:8081/v1/chat/completions".to_string());

        let http_client = reqwest::Client::builder()
            .user_agent("Mozilla/5.0 (Macintosh; Intel Mac OS X 14_0) AppleWebKit/537.36 Chrome/128.0 Safari/537.36")
            .timeout(std::time::Duration::from_secs(45))
            .build()
            .unwrap_or_default();

        let state = Self {
            data_dir,
            static_dir,
            db_path,
            session_secret,
            db_lock: Arc::new(Mutex::new(())),
            http_client,
            qwen_url,
        };
        state.init_sqlite_tables();
        state
    }

    pub fn open_db(&self) -> Result<Connection, String> {
        if let Some(parent) = self.db_path.parent() {
            let _ = fs::create_dir_all(parent);
        }
        let conn = Connection::open(&self.db_path).map_err(|e| e.to_string())?;
        let _ = conn.execute_batch(
            "PRAGMA journal_mode = WAL; PRAGMA synchronous = NORMAL; PRAGMA busy_timeout = 5000;",
        );
        Ok(conn)
    }

    pub fn init_sqlite_tables(&self) {
        let _guard = self.db_lock.lock().ok();
        if let Ok(conn) = self.open_db() {
            for table in [
                "novels", "chapters", "reading_progress", "reading_progress_backend",
                "bookmarks", "bookmarks_backend", "vocabulary", "vocabulary_backend",
                "saved_words", "review_logs",
            ] {
                let sql = format!(
                    "CREATE TABLE IF NOT EXISTS {t} (
                        pkey TEXT PRIMARY KEY, data TEXT NOT NULL,
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    );
                    CREATE INDEX IF NOT EXISTS idx_{t}_id ON {t}(json_extract(data, '$.id'));
                    CREATE INDEX IF NOT EXISTS idx_{t}_novel_id ON {t}(json_extract(data, '$.novel_id'));
                    CREATE INDEX IF NOT EXISTS idx_{t}_user_id ON {t}(json_extract(data, '$.user_id'));",
                    t = table
                );
                let _ = conn.execute_batch(&sql);
            }
        }
    }

    pub fn load_users(&self) -> HashMap<String, Value> {
        let path = self.data_dir.join("users.json");
        if let Ok(raw) = fs::read_to_string(&path) {
            if let Ok(map) = serde_json::from_str::<HashMap<String, Value>>(&raw) {
                return map;
            }
        }
        let mut default_map = HashMap::new();
        default_map.insert(
            "test1".to_string(),
            json!({
                "id": "user_test1", "username": "test1", "email": "test1@truyencloud.vn",
                "password": hash_password("Diempro001"), "display_name": "Diễm Trần (test1)",
                "bio": "Kẻ du hành qua vạn trang sách", "vip_tier": "✦ VIP Tinh Anh 2026",
                "avatar_url": "/static/user_avatar.jpg", "banner_url": "/static/user_banner.jpg",
                "auth_provider": "email", "is_logged_in": true, "is_admin": true
            }),
        );
        default_map
    }

    pub fn save_users(&self, users: &HashMap<String, Value>) -> Result<(), String> {
        let path = self.data_dir.join("users.json");
        let content = serde_json::to_string_pretty(users).map_err(|e| e.to_string())?;
        fs::write(path, content).map_err(|e| e.to_string())
    }

    pub fn verify_bearer(&self, auth_header: Option<&str>, grace_secs: i64) -> Option<TokenPayload> {
        let raw = auth_header?.trim();
        let token = raw
            .strip_prefix("Bearer ")
            .or_else(|| raw.strip_prefix("bearer "))
            .unwrap_or(raw)
            .trim();
        verify_token(&self.session_secret, token, grace_secs)
    }

    pub fn load_all_novels(&self) -> Vec<Novel> {
        let mut seen = HashSet::new();
        let mut list = Vec::new();
        {
            let _guard = self.db_lock.lock().ok();
            if let Ok(conn) = self.open_db() {
                if let Ok(mut stmt) = conn.prepare("SELECT data FROM novels ORDER BY updated_at DESC") {
                    if let Ok(rows) = stmt.query_map([], |row| row.get::<_, String>(0)) {
                        for raw in rows.flatten() {
                            if let Ok(novel) = serde_json::from_str::<Novel>(&raw) {
                                if !novel.id.is_empty() && !novel.title.is_empty() && seen.insert(novel.id.clone()) {
                                    list.push(novel);
                                }
                            }
                        }
                    }
                }
            }
        }
        let novels_dir = self.data_dir.join("novels");
        if let Ok(entries) = fs::read_dir(&novels_dir) {
            for entry in entries.flatten() {
                let info_path = entry.path().join("info.json");
                if let Ok(raw) = fs::read_to_string(&info_path) {
                    if let Ok(novel) = serde_json::from_str::<Novel>(&raw) {
                        if !novel.id.is_empty() && !novel.title.is_empty() {
                            if seen.insert(novel.id.clone()) {
                                list.push(novel);
                            } else if let Some(ex) = list.iter_mut().find(|n| n.id == novel.id) {
                                if ex.translated_chapters == 0 && novel.translated_chapters > 0 {
                                    ex.translated_chapters = novel.translated_chapters;
                                }
                                if novel.overnight_enabled {
                                    ex.overnight_enabled = true;
                                }
                            }
                        }
                    }
                }
            }
        }
        list
    }

    pub fn get_novel(&self, novel_id: &str) -> Option<Novel> {
        let info_path = self.data_dir.join("novels").join(novel_id).join("info.json");
        if let Ok(raw) = fs::read_to_string(&info_path) {
            if let Ok(novel) = serde_json::from_str::<Novel>(&raw) {
                return Some(novel);
            }
        }
        let _guard = self.db_lock.lock().ok();
        let conn = self.open_db().ok()?;
        let mut stmt = conn
            .prepare("SELECT data FROM novels WHERE pkey = ?1 OR json_extract(data, '$.id') = ?1 LIMIT 1")
            .ok()?;
        let raw: String = stmt.query_row(params![novel_id], |r| r.get(0)).ok()?;
        serde_json::from_str(&raw).ok()
    }

    pub fn get_novel_toc_and_translated(&self, novel_id: &str) -> (Vec<Value>, Vec<i32>) {
        let novel_dir = self.data_dir.join("novels").join(novel_id);
        let mut translated_nums = Vec::new();
        if let Ok(entries) = fs::read_dir(novel_dir.join("chapters")) {
            for e in entries.flatten() {
                let fname = e.file_name().to_string_lossy().to_string();
                if let Some(num_str) = fname.strip_prefix("chapter_").and_then(|s| s.strip_suffix(".json")) {
                    if let Ok(num) = num_str.parse::<i32>() {
                        if let Ok(raw) = fs::read_to_string(e.path()) {
                            if raw.contains("\"translated_content\": \"") {
                                translated_nums.push(num);
                            }
                        }
                    }
                }
            }
        }
        translated_nums.sort_unstable();

        if let Ok(raw) = fs::read_to_string(novel_dir.join("chapters_index.json")) {
            if let Ok(arr) = serde_json::from_str::<Vec<Value>>(&raw) {
                if !arr.is_empty() {
                    return (arr, translated_nums);
                }
            }
        }
        let toc = self
            .get_chapters_for_novel(novel_id)
            .iter()
            .map(|c| {
                json!({
                    "chapter_number": c.chapter_number, "id": c.chapter_number.to_string(),
                    "title": c.title, "title_vi": c.title_vi.clone().unwrap_or_default(), "url": c.source_url,
                })
            })
            .collect();
        (toc, translated_nums)
    }

    pub fn get_chapter_value(&self, novel_id: &str, chapter_number: i32) -> Option<Value> {
        let novel_dir = self.data_dir.join("novels").join(novel_id);
        let ch_file = novel_dir
            .join("chapters")
            .join(format!("chapter_{}.json", chapter_number));
        if let Ok(raw) = fs::read_to_string(&ch_file) {
            if let Ok(val) = serde_json::from_str::<Value>(&raw) {
                return Some(val);
            }
        }
        {
            let _guard = self.db_lock.lock().ok();
            if let Ok(conn) = self.open_db() {
                let pkey = format!("{}_{}", novel_id, chapter_number);
                if let Ok(mut stmt) = conn.prepare("SELECT data FROM chapters WHERE pkey = ?1 LIMIT 1") {
                    if let Ok(raw) = stmt.query_row(params![pkey], |r| r.get::<_, String>(0)) {
                        if let Ok(val) = serde_json::from_str::<Value>(&raw) {
                            return Some(val);
                        }
                    }
                }
            }
        }
        // Fallback: lấy URL và tiêu đề chương từ `chapters_index.json` nếu tệp `chapter_<num>.json` chưa từng được cào
        if let Ok(raw) = fs::read_to_string(novel_dir.join("chapters_index.json")) {
            if let Ok(arr) = serde_json::from_str::<Vec<Value>>(&raw) {
                let found = arr.iter().enumerate().find_map(|(idx, item)| {
                    let cnum = item
                        .get("chapter_number")
                        .and_then(|v| v.as_i64())
                        .unwrap_or((idx + 1) as i64) as i32;
                    if cnum == chapter_number { Some(item.clone()) } else { None }
                });
                if let Some(mut item) = found {
                    if let Some(obj) = item.as_object_mut() {
                        obj.insert("novel_id".into(), Value::String(novel_id.to_string()));
                        obj.insert("chapter_number".into(), json!(chapter_number));
                    }
                    return Some(item);
                }
            }
        }
        None
    }

    pub fn save_chapter_value(&self, novel_id: &str, chapter_number: i32, val: &Value) {
        let ch_dir = self.data_dir.join("novels").join(novel_id).join("chapters");
        let _ = fs::create_dir_all(&ch_dir);
        if let Ok(pretty) = serde_json::to_string_pretty(val) {
            let _ = fs::write(ch_dir.join(format!("chapter_{}.json", chapter_number)), pretty);
        }
        let _ = self.upsert_json_row("chapters", &format!("{}_{}", novel_id, chapter_number), val);
    }

    pub fn upsert_json_row(&self, table: &str, pkey: &str, value: &Value) -> Result<(), String> {
        let _guard = self.db_lock.lock().ok();
        let conn = self.open_db()?;
        let raw = serde_json::to_string(value).map_err(|e| e.to_string())?;
        let sql = format!(
            "INSERT INTO {} (pkey, data, updated_at) VALUES (?1, ?2, CURRENT_TIMESTAMP)
             ON CONFLICT(pkey) DO UPDATE SET data = excluded.data, updated_at = CURRENT_TIMESTAMP",
            table
        );
        conn.execute(&sql, params![pkey, raw]).map_err(|e| e.to_string())?;
        Ok(())
    }

    pub fn get_chapters_for_novel(&self, novel_id: &str) -> Vec<Chapter> {
        let _guard = self.db_lock.lock().ok();
        let Ok(conn) = self.open_db() else { return Vec::new(); };
        let Ok(mut stmt) = conn.prepare("SELECT data FROM chapters WHERE json_extract(data, '$.novel_id') = ?1") else {
            return Vec::new();
        };
        let mut list = Vec::new();
        if let Ok(rows) = stmt.query_map(params![novel_id], |r| r.get::<_, String>(0)) {
            for raw in rows.flatten() {
                if let Ok(val) = serde_json::from_str::<Value>(&raw) {
                    list.push(Chapter {
                        novel_id: novel_id.to_string(),
                        chapter_number: val.get("chapter_number").and_then(|v| v.as_i64()).unwrap_or(1) as i32,
                        title: val.get("title").and_then(|v| v.as_str()).unwrap_or("").to_string(),
                        title_vi: val.get("title_vi").and_then(|v| v.as_str()).map(ToOwned::to_owned),
                        content_raw: val.get("original_content").or_else(|| val.get("content_raw")).and_then(|v| v.as_str()).unwrap_or("").to_string(),
                        content_translated: val.get("translated_content").or_else(|| val.get("content_translated")).and_then(|v| v.as_str()).map(ToOwned::to_owned),
                        source_url: val.get("url").or_else(|| val.get("source_url")).and_then(|v| v.as_str()).unwrap_or("").to_string(),
                        is_translated: val.get("translated_content").and_then(|v| v.as_str()).map(|s| !s.is_empty()).unwrap_or(false),
                        ..Default::default()
                    });
                }
            }
        }
        list.sort_by_key(|c| c.chapter_number);
        list
    }
}

fn load_or_create_secret(path: &Path) -> String {
    if let Ok(env_s) = std::env::var("SESSION_SECRET").or_else(|_| std::env::var("JWT_SECRET")) {
        if env_s.len() >= 16 { return env_s; }
    }
    if let Ok(existing) = fs::read_to_string(path) {
        let trimmed = existing.trim().to_string();
        if trimmed.len() >= 32 { return trimmed; }
    }
    let generated = format!("{}{}", uuid::Uuid::new_v4().simple(), uuid::Uuid::new_v4().simple());
    let _ = fs::write(path, &generated);
    generated
}
