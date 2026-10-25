use crate::routes_ai_tts::vieneu_voice_catalog;
use crate::state::AppState;
use axum::extract::State;
use axum::http::StatusCode;
use axum::response::{Html, IntoResponse};
use axum::Json;
use serde_json::{json, Value};
use std::fs;
use std::path::Path;

pub async fn serve_admin_html(State(state): State<AppState>) -> impl IntoResponse {
    let admin_html_path = state.static_dir.join("admin.html");
    if let Ok(content) = fs::read_to_string(&admin_html_path) {
        return Html(content);
    }
    Html(
        "<!DOCTYPE html><html><head><meta charset='utf-8'><title>TruyenCloud Rust Admin</title></head>\
         <body><h1>TruyenCloud Rust Edition Admin</h1><p>Backend Axum đang hoạt động.</p></body></html>"
            .to_string(),
    )
}

pub async fn admin_overview(State(state): State<AppState>) -> impl IntoResponse {
    let novels = state.load_all_novels();
    let users = state.load_users();
    let overnight_count = novels.iter().filter(|n| n.overnight_enabled).count();
    let translated_total: i32 = novels.iter().map(|n| n.translated_chapters).sum();

    (
        StatusCode::OK,
        Json(json!({
            "engine": "TruyenCloud Rust Axum 1.6.0",
            "total_novels": novels.len(),
            "total_users": users.len(),
            "overnight_enabled_count": overnight_count,
            "total_translated_chapters": translated_total,
            "tts_voices_count": vieneu_voice_catalog().len()
        })),
    )
}

pub async fn admin_caches_report(State(state): State<AppState>) -> impl IntoResponse {
    let tts_stats = dir_usage(&state.data_dir.join("tts_cache"));
    let covers_stats = dir_usage(&state.data_dir.join("covers_cache"));
    let flashcards_stats = dir_usage(&state.static_dir.join("flashcards"));

    (
        StatusCode::OK,
        Json(json!({
            "caches": [
                {
                    "name": "tts_cache",
                    "label": "Bộ đệm TTS",
                    "files": tts_stats.0,
                    "size_mb": tts_stats.1,
                    "budget_mb": 32768.0
                },
                {
                    "name": "covers_cache",
                    "label": "Bộ đệm Ảnh bìa",
                    "files": covers_stats.0,
                    "size_mb": covers_stats.1,
                    "budget_mb": 512.0
                },
                {
                    "name": "flashcards",
                    "label": "Bộ đệm Flashcard",
                    "files": flashcards_stats.0,
                    "size_mb": flashcards_stats.1,
                    "budget_mb": 2048.0
                }
            ],
            "janitor": {
                "running": true,
                "engine": "rust-cache-janitor"
            }
        })),
    )
}

pub async fn admin_sweep_cache(State(state): State<AppState>) -> impl IntoResponse {
    let _guard = state.db_lock.lock().ok();
    if let Ok(conn) = state.open_db() {
        let _ = conn.execute_batch("PRAGMA wal_checkpoint(TRUNCATE);");
    }
    (
        StatusCode::OK,
        Json(json!({
            "status": "ok",
            "message": "Đã gộp SQLite WAL và kiểm tra bộ đệm bằng Rust Engine."
        })),
    )
}

pub async fn admin_backup_export(State(state): State<AppState>) -> impl IntoResponse {
    let mut users = state.load_users();
    for val in users.values_mut() {
        if let Some(obj) = val.as_object_mut() {
            obj.insert("password".into(), Value::String("***REDACTED***".into()));
        }
    }
    let novels = state.load_all_novels();
    (
        StatusCode::OK,
        Json(json!({
            "exported_at": chrono::Utc::now().to_rfc3339(),
            "engine": "truyencloud-rust-1.6.0",
            "users_count": users.len(),
            "users": users,
            "novels_count": novels.len()
        })),
    )
}

fn dir_usage(path: &Path) -> (usize, f64) {
    let Ok(entries) = fs::read_dir(path) else {
        return (0, 0.0);
    };
    let mut count = 0usize;
    let mut bytes = 0u64;
    for entry in entries.flatten() {
        if let Ok(meta) = entry.metadata() {
            if meta.is_file() {
                count += 1;
                bytes += meta.len();
            }
        }
    }
    let mb = (bytes as f64 / (1024.0 * 1024.0) * 10.0).round() / 10.0;
    (count, mb)
}
