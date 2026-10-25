use crate::state::AppState;
use axum::extract::{Query, State};
use axum::http::{HeaderMap, StatusCode};
use axum::response::IntoResponse;
use axum::Json;
use rusqlite::params;
use serde::Deserialize;
use serde_json::{json, Value};
use std::fs;

#[derive(Debug, Deserialize)]
pub struct PlatformQuery {
    #[serde(default = "default_android")]
    pub platform: String,
}

fn default_android() -> String {
    "android".to_string()
}

pub async fn get_progress(State(state): State<AppState>, headers: HeaderMap) -> impl IntoResponse {
    let user_id = extract_user_id(&state, &headers);
    let rows = query_user_rows(&state, "reading_progress", &user_id);
    (
        StatusCode::OK,
        Json(json!({
            "user_id": user_id,
            "items": rows,
            "total": rows.len()
        })),
    )
}

pub async fn post_progress(
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
    if let Some(obj) = payload.as_object_mut() {
        obj.insert("user_id".into(), Value::String(user_id.clone()));
    }
    let pkey = format!("{}:{}", user_id, novel_id);
    let _ = state.upsert_json_row("reading_progress", &pkey, &payload);
    (StatusCode::OK, Json(json!({"status": "ok", "pkey": pkey})))
}

pub async fn sync_vocabulary(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(payload): Json<Value>,
) -> impl IntoResponse {
    let user_id = extract_user_id(&state, &headers);
    if let Some(items) = payload.get("items").and_then(|v| v.as_array()) {
        for item in items {
            let mut doc = item.clone();
            let id = doc
                .get("id")
                .or_else(|| doc.get("word"))
                .and_then(|v| v.as_str())
                .unwrap_or("word")
                .to_string();
            if let Some(obj) = doc.as_object_mut() {
                obj.insert("user_id".into(), Value::String(user_id.clone()));
            }
            let pkey = format!("{}:{}", user_id, id);
            let _ = state.upsert_json_row("vocabulary", &pkey, &doc);
        }
    }
    let all = query_user_rows(&state, "vocabulary", &user_id);
    (
        StatusCode::OK,
        Json(json!({
            "status": "ok",
            "user_id": user_id,
            "items": all,
            "total": all.len()
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
                "version_code": 0,
                "version_name": "",
                "min_version_code": 0,
                "download_url": "",
                "file_size_bytes": 0,
                "changelog": "",
                "release_date": "",
                "is_mandatory": false,
                "sha256": "",
                "apk_exists": false,
                "platform": "ios"
            })),
        );
    }

    let version_file = state.data_dir.join("app_version.json");
    let mut info: Value = fs::read_to_string(&version_file)
        .ok()
        .and_then(|s| serde_json::from_str(&s).ok())
        .unwrap_or_else(|| {
            json!({
                "version_code": 20,
                "version_name": "1.6.0",
                "min_version_code": 1,
                "download_url": "/static/truyencloud-latest.apk",
                "changelog": "TruyenCloud v1.6.0 (Rust + KMP)",
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

fn query_user_rows(state: &AppState, table: &str, user_id: &str) -> Vec<Value> {
    let _guard = state.db_lock.lock().ok();
    let Ok(conn) = state.open_db() else {
        return Vec::new();
    };
    let sql = format!(
        "SELECT data FROM {} WHERE json_extract(data, '$.user_id') = ?1 ORDER BY updated_at DESC",
        table
    );
    let Ok(mut stmt) = conn.prepare(&sql) else {
        return Vec::new();
    };
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
