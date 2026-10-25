mod routes_admin;
mod routes_ai_tts;
mod routes_auth;
mod routes_extra;
mod routes_novels;
mod routes_sync;
mod scraper;
mod state;

use axum::routing::{delete, get, post};
use axum::{Json, Router};
use serde_json::json;
use state::AppState;
use std::net::SocketAddr;
use std::path::PathBuf;
use tower_http::cors::CorsLayer;
use tower_http::services::ServeDir;

pub fn build_router(state: AppState) -> Router {
    let static_service = ServeDir::new(state.static_dir.clone());
    let flashcards_service = ServeDir::new(state.static_dir.join("flashcards"));

    Router::new()
        .route("/v1/health", get(health_check))
        // Auth routes
        .route("/v1/auth/login", post(routes_auth::login))
        .route("/v1/auth/refresh", post(routes_auth::refresh_token))
        .route("/v1/auth/me", get(routes_auth::get_me))
        .route("/v1/auth/change-password", post(routes_auth::change_password))
        // Novel & Chapter routes
        .route("/v1/novels/discover", get(routes_novels::discover_novels))
        .route("/v1/novels/search", get(routes_novels::search_novels))
        .route("/v1/novels/suggest", get(routes_novels::suggest_novels))
        .route("/v1/novels/crawl", post(scraper::scrape_url))
        .route("/v1/novels/:novel_id", get(routes_novels::get_novel_detail))
        .route(
            "/v1/novels/:novel_id/check-updates",
            post(routes_extra::check_novel_updates),
        )
        .route(
            "/v1/novels/:novel_id/chapters/:chapter_number",
            get(routes_novels::get_chapter),
        )
        .route(
            "/v1/novels/:novel_id/chapters/:chapter_number/recrawl",
            post(routes_novels::get_chapter),
        )
        .route(
            "/v1/novels/:novel_id/chapters/:chapter_number/recap",
            get(routes_novels::get_chapter_recap),
        )
        .route(
            "/v1/novels/:novel_id/export/epub",
            get(routes_novels::export_novel_epub),
        )
        .route(
            "/v1/novels/:novel_id/glossary",
            get(routes_extra::get_glossary).put(routes_extra::put_glossary),
        )
        // Cloud Sync, Bookmarks, Vocabulary & OTA Update routes
        .route(
            "/v1/progress",
            get(routes_sync::get_all_progress).post(routes_sync::sync_progress),
        )
        .route(
            "/v1/progress/:novel_id",
            get(routes_sync::get_one_progress),
        )
        .route(
            "/v1/bookmarks",
            get(routes_sync::pull_bookmarks).post(routes_sync::sync_bookmarks),
        )
        .route("/v1/bookmarks/sync", post(routes_sync::sync_bookmarks))
        .route(
            "/v1/bookmarks/:bookmark_id",
            delete(routes_sync::delete_bookmark),
        )
        .route(
            "/v1/vocabulary/sync",
            get(routes_sync::pull_vocabulary).post(routes_sync::push_vocabulary),
        )
        .route("/v1/app/version", get(routes_sync::check_app_update))
        .route("/v1/app/check-update", get(routes_sync::check_app_update))
        // AI Translation, VieNeu-TTS & Scraper routes
        .route("/v1/translate", post(routes_ai_tts::translate_chapter))
        .route("/v1/translate/batch", post(routes_ai_tts::batch_translate))
        .route("/v1/jobs/:job_id", get(routes_ai_tts::get_job_status))
        .route(
            "/v1/overnight/status",
            get(routes_ai_tts::get_overnight_status),
        )
        .route("/v1/tts/voices", get(routes_ai_tts::list_voices))
        .route(
            "/v1/tts/stream",
            get(routes_ai_tts::stream_tts).post(routes_ai_tts::stream_tts),
        )
        .route("/v1/tts/prerender", post(routes_ai_tts::prerender_tts))
        .route("/v1/scrape", post(scraper::scrape_url))
        // Settings, Lore, Companion & English Tutor routes
        .route("/v1/settings", get(routes_extra::get_settings))
        .route("/v1/settings/models", get(routes_extra::get_models))
        .route("/v1/settings/models/refresh", post(routes_extra::get_models))
        .route("/v1/settings/models/select", post(routes_extra::select_model))
        .route(
            "/v1/settings/profile",
            get(routes_extra::get_profile).post(routes_extra::update_profile),
        )
        .route("/v1/settings/api-key", post(routes_extra::update_api_key))
        .route("/v1/lore/explain", post(routes_extra::explain_lore))
        .route("/v1/companion/chat", post(routes_extra::companion_chat))
        .route(
            "/v1/companion/chat/stream",
            post(routes_extra::companion_chat),
        )
        .route(
            "/v1/english/explain-word",
            post(routes_extra::explain_english_word),
        )
        .route(
            "/v1/english/explain-words-batch",
            post(routes_extra::explain_english_words_batch),
        )
        .route(
            "/v1/english/translate-sentence",
            post(routes_extra::translate_english_sentence),
        )
        // Web Admin Dashboard & Operations routes
        .route("/admin", get(routes_admin::serve_admin_html))
        .route("/v1/admin/overview", get(routes_admin::admin_overview))
        .route(
            "/v1/admin/system/caches",
            get(routes_admin::admin_caches_report),
        )
        .route(
            "/v1/admin/system/sweep-cache",
            post(routes_admin::admin_sweep_cache),
        )
        .route(
            "/v1/admin/backup/export",
            get(routes_admin::admin_backup_export),
        )
        .nest_service("/static", static_service)
        .nest_service("/flashcards", flashcards_service)
        .layer(CorsLayer::permissive())
        .with_state(state)
}

async fn health_check() -> Json<serde_json::Value> {
    Json(json!({
        "status": "ok",
        "service": "TruyenCloud Rust Backend (Axum + Tokio + SQLite)",
        "version": "1.6.1-rust",
        "runtime": "rust-native",
        "engine": "Rust Axum + Qwen C++ + VieNeu-TTS + FSRS Core"
    }))
}

#[tokio::main]
async fn main() {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "truyencloud_server=info,tower_http=info".into()),
        )
        .init();

    let default_root = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .and_then(|p| p.parent())
        .and_then(|p| p.parent())
        .map(|p| p.join("server"))
        .unwrap_or_else(|| PathBuf::from("."));

    let server_root = std::env::var("TRUYENCLOUD_SERVER_DIR")
        .map(PathBuf::from)
        .unwrap_or(default_root);

    let port: u16 = std::env::var("TRUYENCLOUD_RUST_PORT")
        .ok()
        .and_then(|s| s.parse().ok())
        .unwrap_or(8000);

    let state = AppState::new(&server_root);
    let app = build_router(state);

    let addr = SocketAddr::from(([0, 0, 0, 0], port));
    tracing::info!("🚀 TruyenCloud Rust Server đang lắng nghe tại http://{}", addr);
    tracing::info!("📂 Thư mục dữ liệu gốc: {}", server_root.display());

    let listener = tokio::net::TcpListener::bind(addr)
        .await
        .expect("Không mở được cổng mạng cho Rust Server");
    axum::serve(listener, app).await.expect("Lỗi chạy Axum server");
}
