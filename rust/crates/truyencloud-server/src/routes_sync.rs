use crate::state::AppState;
use axum::body::Body;
use axum::extract::{Path, Query, State};
use axum::http::{header, HeaderMap, Response, StatusCode};
use axum::response::IntoResponse;
use axum::Json;
use rusqlite::params;
use serde::Deserialize;
use serde_json::{json, Value};
use std::fs;
use std::time::{SystemTime, UNIX_EPOCH};

#[derive(Debug, Deserialize)]
pub struct PlatformQuery {
    #[serde(default = "default_android")]
    pub platform: String,
}

fn default_android() -> String {
    "android".to_string()
}

pub fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

pub async fn get_all_progress(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> impl IntoResponse {
    let user_id = extract_user_id(&state, &headers);
    let mut rows = query_user_rows(&state, "reading_progress_backend", &user_id);
    if rows.is_empty() {
        rows = query_user_rows(&state, "reading_progress", &user_id);
    }
    let enriched: Vec<Value> = rows
        .into_iter()
        .map(|mut item| {
            enrich_progress_with_novel(&state, &mut item);
            item
        })
        .collect();
    (StatusCode::OK, Json(enriched))
}

pub async fn get_one_progress(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(novel_id): Path<String>,
) -> impl IntoResponse {
    let user_id = extract_user_id(&state, &headers);
    let rows = query_user_rows(&state, "reading_progress_backend", &user_id);
    if let Some(mut found) = rows
        .into_iter()
        .find(|r| r.get("novel_id").and_then(|v| v.as_str()) == Some(novel_id.as_str()))
    {
        enrich_progress_with_novel(&state, &mut found);
        return (StatusCode::OK, Json(found));
    }
    let mut default_prog = json!({
        "novel_id": novel_id,
        "current_chapter": 0,
        "scroll_position": 0.0,
        "shelf_name": "reading",
        "is_favorite": false,
        "updated_at_ms": 0
    });
    enrich_progress_with_novel(&state, &mut default_prog);
    (StatusCode::OK, Json(default_prog))
}

pub async fn sync_progress(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(mut payload): Json<Value>,
) -> impl IntoResponse {
    let user_id = extract_user_id(&state, &headers);
    let novel_id = payload
        .get("novel_id")
        .and_then(|v| v.as_str())
        .unwrap_or("unknown")
        .to_string();
    let ts_ms = payload
        .get("client_updated_at")
        .and_then(|v| v.as_i64())
        .unwrap_or_else(now_ms);
    let now_iso = chrono::Utc::now().to_rfc3339();

    if let Some(obj) = payload.as_object_mut() {
        obj.insert("user_id".into(), Value::String(user_id.clone()));
        obj.insert("updated_at_ms".into(), json!(ts_ms));
        obj.insert("last_read_at".into(), Value::String(now_iso.clone()));
    }
    enrich_progress_with_novel(&state, &mut payload);
    let pkey = format!("progress_{}_{}", user_id, novel_id);
    let _ = state.upsert_json_row("reading_progress_backend", &pkey, &payload);

    (
        StatusCode::OK,
        Json(json!({
            "status": "success",
            "synced_at": now_iso,
            "updated_at_ms": ts_ms,
            "progress": payload
        })),
    )
}

pub async fn pull_bookmarks(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> impl IntoResponse {
    let user_id = extract_user_id(&state, &headers);
    let mut items = query_user_rows(&state, "bookmarks_backend", &user_id);
    if items.is_empty() {
        items = query_user_rows(&state, "bookmarks", &user_id);
    }
    let body = serde_json::to_string(&items).unwrap_or_else(|_| "[]".to_string());
    Response::builder()
        .status(StatusCode::OK)
        .header(header::CONTENT_TYPE, "application/json")
        .header("X-Server-Time-Ms", now_ms().to_string())
        .body(Body::from(body))
        .unwrap()
}

pub async fn sync_bookmarks(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(payload): Json<Value>,
) -> impl IntoResponse {
    let user_id = extract_user_id(&state, &headers);
    let mut applied = 0usize;
    if let Some(arr) = payload.get("bookmarks").and_then(|v| v.as_array()) {
        for item in arr {
            let mut doc = item.clone();
            let bm_id = doc.get("id").and_then(|v| v.as_str()).unwrap_or("bm").to_string();
            if let Some(obj) = doc.as_object_mut() {
                obj.insert("user_id".into(), Value::String(user_id.clone()));
            }
            let pkey = format!("{}_{}", user_id, bm_id);
            let _ = state.upsert_json_row("bookmarks_backend", &pkey, &doc);
            applied += 1;
        }
    }
    (
        StatusCode::OK,
        Json(json!({
            "status": "success",
            "count": applied,
            "applied": applied,
            "unchanged": 0,
            "stale_ids": [],
            "synced_at": chrono::Utc::now().to_rfc3339(),
            "server_time_ms": now_ms()
        })),
    )
}

pub async fn delete_bookmark(Path(bookmark_id): Path<String>) -> impl IntoResponse {
    (StatusCode::OK, Json(json!({"status": "deleted", "id": bookmark_id})))
}

pub async fn pull_vocabulary(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> impl IntoResponse {
    let user_id = extract_user_id(&state, &headers);
    let words: Vec<Value> = query_user_rows(&state, "saved_words", &user_id)
        .into_iter()
        .map(|r| r.get("data").cloned().unwrap_or(r))
        .collect();
    let logs: Vec<Value> = query_user_rows(&state, "review_logs", &user_id)
        .into_iter()
        .map(|r| r.get("data").cloned().unwrap_or(r))
        .collect();
    (
        StatusCode::OK,
        Json(json!({
            "words": words,
            "review_logs": logs,
            "synced_at": chrono::Utc::now().to_rfc3339(),
            "server_time_ms": now_ms()
        })),
    )
}

pub async fn push_vocabulary(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(payload): Json<Value>,
) -> impl IntoResponse {
    let user_id = extract_user_id(&state, &headers);
    let mut words_synced = 0usize;
    let mut logs_synced = 0usize;

    if let Some(words) = payload.get("words").and_then(|v| v.as_array()) {
        for w in words {
            let wid = w.get("id").and_then(|v| v.as_str()).unwrap_or("w");
            let pkey = format!("{}_{}", user_id, wid);
            let row = json!({"id": pkey, "user_id": user_id, "word_id": wid, "data": w});
            let _ = state.upsert_json_row("saved_words", &pkey, &row);
            words_synced += 1;
        }
    }
    if let Some(logs) = payload.get("review_logs").and_then(|v| v.as_array()) {
        for l in logs {
            let lid = l.get("id").and_then(|v| v.as_str()).unwrap_or("l");
            let pkey = format!("{}_{}", user_id, lid);
            let row = json!({"id": pkey, "user_id": user_id, "data": l});
            let _ = state.upsert_json_row("review_logs", &pkey, &row);
            logs_synced += 1;
        }
    }
    (
        StatusCode::OK,
        Json(json!({
            "status": "success",
            "words_synced": words_synced,
            "words_stale": 0,
            "stale_word_ids": [],
            "logs_synced": logs_synced,
            "logs_skipped": 0,
            "synced_at": chrono::Utc::now().to_rfc3339(),
            "server_time_ms": now_ms()
        })),
    )
}

pub async fn check_app_update(
    State(state): State<AppState>,
    Query(q): Query<PlatformQuery>,
) -> impl IntoResponse {
    let platform = q.platform.trim().to_lowercase();
    if platform == "ios" {
        return (
            StatusCode::OK,
            Json(json!({
                "version_code": 0, "version_name": "", "min_version_code": 0,
                "download_url": "", "file_size_bytes": 0, "changelog": "",
                "release_date": "", "is_mandatory": false, "sha256": "",
                "apk_exists": false, "platform": "ios"
            })),
        );
    }

    let version_file = state.data_dir.join("app_version.json");
    let mut info: Value = fs::read_to_string(&version_file)
        .ok()
        .and_then(|s| serde_json::from_str(&s).ok())
        .unwrap_or_else(|| {
            json!({
                "version_code": 21,
                "version_name": "1.6.1-rust",
                "min_version_code": 1,
                "download_url": "/static/truyencloud-latest.apk",
                "changelog": "TruyenCloud v1.6.1 (Rust Edition Backend)",
                "release_date": "2026-10-25",
                "is_mandatory": false,
                "sha256": ""
            })
        });

    let apk_path = state.static_dir.join("truyencloud-latest.apk");
    let (apk_exists, file_size) = match fs::metadata(&apk_path) {
        Ok(m) => (true, m.len()),
        Err(_) => (false, 0),
    };
    if let Some(obj) = info.as_object_mut() {
        obj.insert("apk_exists".into(), Value::Bool(apk_exists));
        if file_size > 0 {
            obj.insert("file_size_bytes".into(), json!(file_size));
        }
        obj.insert("platform".into(), Value::String("android".into()));
    }
    (StatusCode::OK, Json(info))
}

fn enrich_progress_with_novel(state: &AppState, item: &mut Value) {
    let Some(nid) = item.get("novel_id").and_then(|v| v.as_str()).map(ToOwned::to_owned) else {
        return;
    };
    if let Some(novel) = state.get_novel(&nid) {
        if let Some(obj) = item.as_object_mut() {
            obj.insert(
                "novel".into(),
                json!({
                    "id": novel.id, "title": novel.title, "title_vi": novel.display_title(),
                    "author": novel.display_author(), "cover_url": novel.cover_url,
                    "total_chapters": novel.total_chapters, "source_id": novel.source_id,
                    "source_url": novel.source_url, "description": novel.description,
                    "status": novel.status, "translated_chapters": novel.translated_chapters,
                    "overnight_enabled": novel.overnight_enabled, "category": ""
                }),
            );
        }
    }
}

fn extract_user_id(state: &AppState, headers: &HeaderMap) -> String {
    let auth = headers
        .get("authorization")
        .or_else(|| headers.get("x-auth-token"))
        .and_then(|v| v.to_str().ok());
    state
        .verify_bearer(auth, 0)
        .map(|p| p.sub)
        .unwrap_or_else(|| "local".to_string())
}

pub fn query_user_rows(state: &AppState, table: &str, user_id: &str) -> Vec<Value> {
    let _guard = state.db_lock.lock().ok();
    let Ok(conn) = state.open_db() else { return Vec::new(); };
    let sql = format!(
        "SELECT data FROM {} WHERE json_extract(data, '$.user_id') = ?1 ORDER BY updated_at DESC",
        table
    );
    let Ok(mut stmt) = conn.prepare(&sql) else { return Vec::new(); };
    let mut list = Vec::new();
    if let Ok(rows) = stmt.query_map(params![user_id], |r| r.get::<_, String>(0)) {
        for raw in rows.flatten() {
            if let Ok(val) = serde_json::from_str::<Value>(&raw) {
                list.push(val);
            }
        }
    }
    list
}
