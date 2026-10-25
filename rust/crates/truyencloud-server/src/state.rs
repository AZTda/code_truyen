use rusqlite::{params, Connection};
use serde_json::{json, Value};
use std::collections::HashMap;
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use truyencloud_core::crypto::{hash_password, verify_token, TokenPayload};
use truyencloud_core::models::{Chapter, GlossaryMap, Novel};

#[derive(Clone)]
pub struct AppState {
    pub data_dir: PathBuf,
    pub static_dir: PathBuf,
    pub db_path: PathBuf,
    pub session_secret: String,
    pub db_lock: Arc<Mutex<()>>,
    pub http_client: reqwest::Client,
}

impl AppState {
    pub fn new(server_root: &Path) -> Self {
        let data_dir = server_root.join("data");
        let static_dir = server_root.join("static");
        let _ = fs::create_dir_all(&data_dir);
        let _ = fs::create_dir_all(&static_dir);

        let db_path = data_dir.join("truyencloud.db");
        let session_secret = load_or_create_secret(&data_dir.join(".session_secret"));
        let http_client = reqwest::Client::builder()
            .user_agent("Mozilla/5.0 (Macintosh; Intel Mac OS X 14_0) AppleWebKit/537.36 Chrome/128.0 Safari/537.36")
            .timeout(std::time::Duration::from_secs(20))
            .build()
            .unwrap_or_default();

        let state = Self {
            data_dir,
            static_dir,
            db_path,
            session_secret,
            db_lock: Arc::new(Mutex::new(())),
            http_client,
        };
        state.init_sqlite_tables();
        state
    }

    pub fn open_db(&self) -> Result<Connection, String> {
        let conn = Connection::open(&self.db_path).map_err(|e| e.to_string())?;
        let _ = conn.execute_batch(
            "PRAGMA journal_mode = WAL;
             PRAGMA synchronous = NORMAL;
             PRAGMA busy_timeout = 5000;",
        );
        Ok(conn)
    }

    pub fn init_sqlite_tables(&self) {
        let _guard = self.db_lock.lock().ok();
        if let Ok(conn) = self.open_db() {
            for table in ["novels", "chapters", "reading_progress", "bookmarks", "vocabulary"] {
                let sql = format!(
                    "CREATE TABLE IF NOT EXISTS {t} (
                        pkey TEXT PRIMARY KEY,
                        data TEXT NOT NULL,
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
                "id": "user_test1",
                "username": "test1",
                "email": "test1@truyencloud.vn",
                "password": hash_password("truyencloud2026"),
                "display_name": "Diễm Trần (test1)",
                "bio": "Kẻ du hành qua vạn trang sách",
                "vip_tier": "✦ VIP Tinh Anh 2026",
                "avatar_url": "/static/user_avatar.jpg",
                "banner_url": "/static/user_banner.jpg",
                "auth_provider": "email",
                "is_logged_in": true,
                "is_admin": true
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
        let _guard = self.db_lock.lock().ok();
        let Ok(conn) = self.open_db() else {
            return Vec::new();
        };
        let Ok(mut stmt) = conn.prepare("SELECT data FROM novels ORDER BY updated_at DESC") else {
            return Vec::new();
        };
        let rows = stmt
            .query_map([], |row| row.get::<_, String>(0))
            .ok();
        let mut novels = Vec::new();
        if let Some(iter) = rows {
            for item in iter.flatten() {
                if let Ok(novel) = serde_json::from_str::<Novel>(&item) {
                    if !novel.id.is_empty() && !novel.title.is_empty() {
                        novels.push(novel);
                    }
                }
            }
        }
        novels
    }

    pub fn get_novel(&self, novel_id: &str) -> Option<Novel> {
        let _guard = self.db_lock.lock().ok();
        let conn = self.open_db().ok()?;
        let mut stmt = conn
            .prepare("SELECT data FROM novels WHERE pkey = ?1 OR json_extract(data, '$.id') = ?1 LIMIT 1")
            .ok()?;
        let raw: String = stmt.query_row(params![novel_id], |r| r.get(0)).ok()?;
        serde_json::from_str(&raw).ok()
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
        conn.execute(&sql, params![pkey, raw])
            .map_err(|e| e.to_string())?;
        Ok(())
    }

    pub fn get_chapters_for_novel(&self, novel_id: &str) -> Vec<Chapter> {
        let _guard = self.db_lock.lock().ok();
        let Ok(conn) = self.open_db() else {
            return Vec::new();
        };
        let Ok(mut stmt) = conn.prepare(
            "SELECT data FROM chapters WHERE json_extract(data, '$.novel_id') = ?1",
        ) else {
            return Vec::new();
        };
        let mut list = Vec::new();
        if let Ok(rows) = stmt.query_map(params![novel_id], |r| r.get::<_, String>(0)) {
            for raw in rows.flatten() {
                if let Ok(ch) = serde_json::from_str::<Chapter>(&raw) {
                    list.push(ch);
                }
            }
        }
        list.sort_by_key(|c| c.chapter_number);
        list
    }

    pub fn load_glossary(&self, novel_id: &str) -> GlossaryMap {
        let safe_id: String = novel_id
            .chars()
            .filter(|c| c.is_alphanumeric() || *c == '_' || *c == '-')
            .collect();
        let path = self.data_dir.join("glossaries").join(format!("{}.json", safe_id));
        fs::read_to_string(path)
            .ok()
            .and_then(|s| serde_json::from_str(&s).ok())
            .unwrap_or_default()
    }

    pub fn save_glossary(&self, novel_id: &str, glossary: &GlossaryMap) -> Result<(), String> {
        let safe_id: String = novel_id
            .chars()
            .filter(|c| c.is_alphanumeric() || *c == '_' || *c == '-')
            .collect();
        let dir = self.data_dir.join("glossaries");
        let _ = fs::create_dir_all(&dir);
        let content = serde_json::to_string_pretty(glossary).map_err(|e| e.to_string())?;
        fs::write(dir.join(format!("{}.json", safe_id)), content).map_err(|e| e.to_string())
    }
}

fn load_or_create_secret(path: &Path) -> String {
    if let Ok(env_s) = std::env::var("SESSION_SECRET").or_else(|_| std::env::var("JWT_SECRET")) {
        if env_s.len() >= 16 {
            return env_s;
        }
    }
    if let Ok(existing) = fs::read_to_string(path) {
        let trimmed = existing.trim().to_string();
        if trimmed.len() >= 32 {
            return trimmed;
        }
    }
    let generated = format!("{}{}", uuid::Uuid::new_v4().simple(), uuid::Uuid::new_v4().simple());
    let _ = fs::write(path, &generated);
    generated
}
