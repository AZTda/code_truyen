use crate::state::AppState;
use axum::body::Body;
use axum::extract::{Path, Query, State};
use axum::http::{header, HeaderMap, Response, StatusCode};
use axum::response::IntoResponse;
use axum::Json;
use rusqlite::params;
use serde::Deserialize;
use serde_json::{json, Value};
use std::collections::HashMap;
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

fn resolve_user_id(state: &AppState, headers: &HeaderMap) -> Result<String, (StatusCode, Json<Value>)> {
    let raw_auth = headers.get("authorization").or_else(|| headers.get("x-auth-token"))
        .and_then(|v| v.to_str().ok()).map(str::trim).unwrap_or("");
    if raw_auth.is_empty() || raw_auth.eq_ignore_ascii_case("Bearer") {
        return Ok("local".to_string());
    }
    state.verify_bearer(Some(raw_auth), 0).map(|p| p.sub).ok_or_else(|| {
        (StatusCode::UNAUTHORIZED, Json(json!({"detail": "Phiên đăng nhập không hợp lệ hoặc đã hết hạn."})))
    })
}

fn merged_progress_rows(state: &AppState, user_id: &str) -> Vec<Value> {
    let mut map: HashMap<String, Value> = HashMap::new();
    for table in ["reading_progress", "reading_progress_backend"] {
        for row in query_user_rows(state, table, user_id) {
            let Some(nid) = row.get("novel_id").and_then(|v| v.as_str()).map(ToOwned::to_owned) else { continue; };
            let row_ts = row.get("updated_at_ms").or_else(|| row.get("client_updated_at")).and_then(|v| v.as_i64()).unwrap_or(0);
            let replace = map.get(&nid).map(|ex| {
                row_ts >= ex.get("updated_at_ms").or_else(|| ex.get("client_updated_at")).and_then(|v| v.as_i64()).unwrap_or(0)
            }).unwrap_or(true);
            if replace { map.insert(nid, row); }
        }
    }
    map.into_values().collect()
}

pub async fn get_all_progress(State(state): State<AppState>, headers: HeaderMap) -> Response<Body> {
    let user_id = match resolve_user_id(&state, &headers) { Ok(u) => u, Err(e) => return e.into_response() };
    let enriched: Vec<Value> = merged_progress_rows(&state, &user_id).into_iter().map(|mut item| {
        enrich_progress_with_novel(&state, &mut item);
        item
    }).collect();
    (StatusCode::OK, Json(enriched)).into_response()
}

pub async fn get_one_progress(State(state): State<AppState>, headers: HeaderMap, Path(novel_id): Path<String>) -> Response<Body> {
    let user_id = match resolve_user_id(&state, &headers) { Ok(u) => u, Err(e) => return e.into_response() };
    if let Some(mut found) = merged_progress_rows(&state, &user_id).into_iter()
        .find(|r| r.get("novel_id").and_then(|v| v.as_str()) == Some(novel_id.as_str()))
    {
        enrich_progress_with_novel(&state, &mut found);
        return (StatusCode::OK, Json(found)).into_response();
    }
    let mut default_prog = json!({
        "novel_id": novel_id, "current_chapter": 0, "scroll_position": 0.0,
        "shelf_name": "reading", "is_favorite": false, "updated_at_ms": 0
    });
    enrich_progress_with_novel(&state, &mut default_prog);
    (StatusCode::OK, Json(default_prog)).into_response()
}

pub async fn sync_progress(State(state): State<AppState>, headers: HeaderMap, Json(mut payload): Json<Value>) -> Response<Body> {
    let user_id = match resolve_user_id(&state, &headers) { Ok(u) => u, Err(e) => return e.into_response() };
    let novel_id = payload.get("novel_id").and_then(|v| v.as_str()).unwrap_or("unknown").to_string();
    let ts_ms = payload.get("client_updated_at").or_else(|| payload.get("updated_at_ms"))
        .and_then(|v| v.as_i64()).filter(|&t| t > 0).unwrap_or_else(now_ms);
    let now_iso = chrono::Utc::now().to_rfc3339();

    if let Some(existing) = merged_progress_rows(&state, &user_id).into_iter()
        .find(|r| r.get("novel_id").and_then(|v| v.as_str()) == Some(novel_id.as_str()))
    {
        let ex_ts = existing.get("updated_at_ms").and_then(|v| v.as_i64()).unwrap_or(0);
        if ex_ts > ts_ms {
            let mut winner = existing;
            enrich_progress_with_novel(&state, &mut winner);
            return (StatusCode::OK, Json(json!({
                "status": "stale_ignored", "synced_at": now_iso, "updated_at_ms": ex_ts, "progress": winner
            }))).into_response();
        }
    }

    if let Some(obj) = payload.as_object_mut() {
        obj.insert("user_id".into(), Value::String(user_id.clone()));
        obj.insert("updated_at_ms".into(), json!(ts_ms));
        obj.insert("last_read_at".into(), Value::String(now_iso.clone()));
    }
    enrich_progress_with_novel(&state, &mut payload);
    let pkey = format!("progress_{}_{}", user_id, novel_id);
    let _ = state.upsert_json_row("reading_progress_backend", &pkey, &payload);
    (StatusCode::OK, Json(json!({
        "status": "success", "synced_at": now_iso, "updated_at_ms": ts_ms, "progress": payload
    }))).into_response()
}

fn merged_bookmark_rows(state: &AppState, user_id: &str) -> HashMap<String, Value> {
    let mut map: HashMap<String, Value> = HashMap::new();
    for table in ["bookmarks", "bookmarks_backend"] {
        for row in query_user_rows(state, table, user_id) {
            let Some(bid) = row.get("id").and_then(|v| v.as_str()).map(ToOwned::to_owned) else { continue; };
            let ts = row.get("updated_at").and_then(|v| v.as_i64()).unwrap_or(0);
            let replace = map.get(&bid).map(|ex| ts >= ex.get("updated_at").and_then(|v| v.as_i64()).unwrap_or(0)).unwrap_or(true);
            if replace { map.insert(bid, row); }
        }
    }
    map
}

pub async fn pull_bookmarks(State(state): State<AppState>, headers: HeaderMap) -> Response<Body> {
    let user_id = match resolve_user_id(&state, &headers) { Ok(u) => u, Err(e) => return e.into_response() };
    let items: Vec<Value> = merged_bookmark_rows(&state, &user_id).into_values().collect();
    let body = serde_json::to_string(&items).unwrap_or_else(|_| "[]".to_string());
    Response::builder().status(StatusCode::OK).header(header::CONTENT_TYPE, "application/json")
        .header("X-Server-Time-Ms", now_ms().to_string()).body(Body::from(body)).unwrap()
}

pub async fn sync_bookmarks(State(state): State<AppState>, headers: HeaderMap, Json(payload): Json<Value>) -> Response<Body> {
    let user_id = match resolve_user_id(&state, &headers) { Ok(u) => u, Err(e) => return e.into_response() };
    let existing = merged_bookmark_rows(&state, &user_id);
    let (mut applied, mut unchanged, mut stale_ids) = (0usize, 0usize, Vec::new());

    if let Some(arr) = payload.get("bookmarks").and_then(|v| v.as_array()) {
        for item in arr {
            let mut doc = item.clone();
            let bm_id = doc.get("id").and_then(|v| v.as_str()).unwrap_or("bm").to_string();
            let inc_ts = doc.get("updated_at").and_then(|v| v.as_i64()).unwrap_or_else(now_ms);
            if let Some(ex) = existing.get(&bm_id) {
                if ex.get("updated_at").and_then(|v| v.as_i64()).unwrap_or(0) > inc_ts {
                    stale_ids.push(bm_id); unchanged += 1; continue;
                }
            }
            if let Some(obj) = doc.as_object_mut() {
                obj.insert("user_id".into(), Value::String(user_id.clone()));
                obj.insert("updated_at".into(), json!(inc_ts));
            }
            let _ = state.upsert_json_row("bookmarks_backend", &format!("{}_{}", user_id, bm_id), &doc);
            applied += 1;
        }
    }
    (StatusCode::OK, Json(json!({
        "status": "success", "count": applied, "applied": applied, "unchanged": unchanged,
        "stale_ids": stale_ids, "synced_at": chrono::Utc::now().to_rfc3339(), "server_time_ms": now_ms()
    }))).into_response()
}

pub async fn delete_bookmark(State(state): State<AppState>, headers: HeaderMap, Path(bookmark_id): Path<String>) -> Response<Body> {
    let user_id = match resolve_user_id(&state, &headers) { Ok(u) => u, Err(e) => return e.into_response() };
    let pkey = format!("{}_{}", user_id, bookmark_id);
    let tombstone = json!({"id": bookmark_id, "user_id": user_id, "deleted": true, "updated_at": now_ms()});
    let _ = state.upsert_json_row("bookmarks_backend", &pkey, &tombstone);
    (StatusCode::OK, Json(json!({"status": "deleted", "id": bookmark_id}))).into_response()
}

pub async fn pull_vocabulary(State(state): State<AppState>, headers: HeaderMap) -> Response<Body> {
    let user_id = match resolve_user_id(&state, &headers) { Ok(u) => u, Err(e) => return e.into_response() };
    let words: Vec<Value> = query_user_rows(&state, "saved_words", &user_id).into_iter().map(|r| r.get("data").cloned().unwrap_or(r)).collect();
    let logs: Vec<Value> = query_user_rows(&state, "review_logs", &user_id).into_iter().map(|r| r.get("data").cloned().unwrap_or(r)).collect();
    (StatusCode::OK, Json(json!({
        "words": words, "review_logs": logs, "synced_at": chrono::Utc::now().to_rfc3339(), "server_time_ms": now_ms()
    }))).into_response()
}

pub async fn push_vocabulary(State(state): State<AppState>, headers: HeaderMap, Json(payload): Json<Value>) -> Response<Body> {
    let user_id = match resolve_user_id(&state, &headers) { Ok(u) => u, Err(e) => return e.into_response() };
    let (mut words_synced, mut logs_synced) = (0usize, 0usize);

    if let Some(words) = payload.get("words").and_then(|v| v.as_array()) {
        for w in words {
            let wid = w.get("id").and_then(|v| v.as_str().map(ToOwned::to_owned).or_else(|| v.as_i64().map(|n| n.to_string())))
                .or_else(|| w.get("word").and_then(|v| v.as_str()).map(|s| s.to_lowercase())).unwrap_or_else(|| "w".to_string());
            let pkey = format!("{}_{}", user_id, wid);
            let _ = state.upsert_json_row("saved_words", &pkey, &json!({"id": pkey, "user_id": user_id, "word_id": wid, "data": w}));
            words_synced += 1;
        }
    }
    if let Some(logs) = payload.get("review_logs").and_then(|v| v.as_array()) {
        for l in logs {
            let lid = l.get("id").and_then(|v| v.as_str().map(ToOwned::to_owned).or_else(|| v.as_i64().map(|n| n.to_string()))).unwrap_or_else(|| "0".to_string());
            let wid = l.get("word_id").or_else(|| l.get("wordId")).and_then(|v| v.as_str()).unwrap_or("");
            let rev_at = l.get("reviewed_at").or_else(|| l.get("reviewedAt")).and_then(|v| v.as_i64()).unwrap_or(0);
            let pkey = format!("{}_{}_{}_{}", user_id, wid, rev_at, lid);
            let _ = state.upsert_json_row("review_logs", &pkey, &json!({"id": pkey, "user_id": user_id, "data": l}));
            logs_synced += 1;
        }
    }
    (StatusCode::OK, Json(json!({
        "status": "success", "words_synced": words_synced, "words_stale": 0, "stale_word_ids": [],
        "logs_synced": logs_synced, "logs_skipped": 0, "synced_at": chrono::Utc::now().to_rfc3339(), "server_time_ms": now_ms()
    }))).into_response()
}

pub async fn check_app_update(
    State(state): State<AppState>,
    Query(q): Query<PlatformQuery>,
) -> impl IntoResponse {
    if q.platform.trim().eq_ignore_ascii_case("ios") {
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
                "version_code": 21, "version_name": "1.6.1-rust", "min_version_code": 1,
                "download_url": "/static/truyencloud-latest.apk",
                "changelog": "TruyenCloud v1.6.1 (Rust Edition Backend)",
                "release_date": "2026-10-25", "is_mandatory": false, "sha256": ""
            })
        });
    let apk_path = state.static_dir.join("truyencloud-latest.apk");
    let (apk_exists, file_size) = fs::metadata(&apk_path).map(|m| (true, m.len())).unwrap_or((false, 0));
    if let Some(obj) = info.as_object_mut() {
        obj.insert("apk_exists".into(), Value::Bool(apk_exists));
        if file_size > 0 { obj.insert("file_size_bytes".into(), json!(file_size)); }
        obj.insert("platform".into(), Value::String("android".into()));
    }
    (StatusCode::OK, Json(info))
}

fn enrich_progress_with_novel(state: &AppState, item: &mut Value) {
    let Some(nid) = item.get("novel_id").and_then(|v| v.as_str()).map(ToOwned::to_owned) else { return; };
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

pub fn query_user_rows(state: &AppState, table: &str, user_id: &str) -> Vec<Value> {
    let _guard = state.db_lock.lock().ok();
    let Ok(conn) = state.open_db() else { return Vec::new(); };
    let sql = format!("SELECT data FROM {} WHERE json_extract(data, '$.user_id') = ?1 ORDER BY updated_at DESC", table);
    let Ok(mut stmt) = conn.prepare(&sql) else { return Vec::new(); };
    let mut list = Vec::new();
    if let Ok(rows) = stmt.query_map(params![user_id], |r| r.get::<_, String>(0)) {
        for raw in rows.flatten() {
            if let Ok(val) = serde_json::from_str::<Value>(&raw) { list.push(val); }
        }
    }
    list
}
