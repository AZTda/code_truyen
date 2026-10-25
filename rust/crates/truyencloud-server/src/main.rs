mod routes_admin;
mod routes_admin_novels;
mod routes_ai_tts;
mod routes_auth;
mod routes_english;
mod routes_extra;
mod routes_images;
mod routes_import;
mod routes_novels;
mod routes_sync;
mod routes_translate;
mod scraper;
mod state;

use axum::extract::DefaultBodyLimit;
use axum::middleware;
use axum::routing::{delete, get, post, put};
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

    let admin_api = Router::new()
        .route("/v1/admin/overview", get(routes_admin::admin_overview))
        .route("/v1/admin/users", get(routes_admin::admin_list_users).post(routes_admin::admin_create_user))
        .route("/v1/admin/users/:username/password", put(routes_admin::admin_update_user_password))
        .route("/v1/admin/users/:username/profile", put(routes_admin::admin_update_user_profile))
        .route("/v1/admin/users/:username/activity", get(routes_admin::admin_user_activity))
        .route("/v1/admin/users/:username", delete(routes_admin::admin_delete_user))
        .route("/v1/admin/novels", get(routes_admin_novels::admin_list_novels))
        .route("/v1/admin/novels/import", post(routes_admin_novels::admin_import_novel))
        .route("/v1/admin/novels/harvester/status", get(routes_admin_novels::admin_harvester_status))
        .route("/v1/admin/novels/harvester/start", post(routes_admin_novels::admin_harvester_start))
        .route("/v1/admin/novels/harvester/stop", post(routes_admin_novels::admin_harvester_stop))
        .route("/v1/admin/novels/overnight/status", get(routes_extra::get_overnight_status))
        .route("/v1/admin/novels/auto-updater/trigger", post(routes_admin_novels::admin_trigger_auto_updater))
        .route("/v1/admin/novels/bulk-action", post(routes_admin_novels::admin_bulk_action))
        .route("/v1/admin/novels/:novel_id", delete(routes_admin_novels::admin_delete_novel))
        .route("/v1/admin/novels/:novel_id/meta", put(routes_admin_novels::admin_update_novel_meta))
        .route("/v1/admin/novels/:novel_id/chapters-preview", get(routes_admin_novels::admin_novel_chapters_preview))
        .route("/v1/admin/novels/:novel_id/chapters/:chapter_num", put(routes_admin_novels::admin_update_chapter))
        .route("/v1/admin/novels/:novel_id/pretranslate", post(routes_admin_novels::admin_pretranslate_novel))
        .route("/v1/admin/novels/:novel_id/download-raw", post(routes_admin_novels::admin_download_raw))
        .route("/v1/admin/novels/:novel_id/overnight", post(routes_admin_novels::admin_start_overnight))
        .route("/v1/admin/novels/:novel_id/overnight/stop", post(routes_admin_novels::admin_stop_overnight))
        .route("/v1/admin/novels/:novel_id/cancel-job", post(routes_admin_novels::admin_cancel_novel_job))
        .route("/v1/admin/novels/:novel_id/prerender-tts", post(routes_admin_novels::admin_prerender_novel_tts))
        .route("/v1/admin/novels/:novel_id/export.epub", get(routes_novels::export_novel_epub))
        .route("/v1/admin/novels/:novel_id/glossary", get(routes_extra::get_glossary).put(routes_extra::put_glossary))
        .route("/v1/admin/system/caches", get(routes_admin::admin_caches_report))
        .route("/v1/admin/system/sweep-cache", post(routes_admin::admin_sweep_cache))
        .route("/v1/admin/system/ai-engines", get(routes_admin::admin_ai_engines))
        .route("/v1/admin/ai/playground-translate", post(routes_admin::admin_playground_translate))
        .route("/v1/admin/backup/export", get(routes_admin::admin_backup_export))
        .route("/v1/admin/app/upload-apk", post(routes_import::admin_upload_apk))
        .route_layer(middleware::from_fn_with_state(
            state.clone(),
            routes_admin::admin_auth_middleware,
        ));

    Router::new()
        .route("/", get(routes_import::serve_download_html))
        .route("/download", get(routes_import::serve_download_html))
        .route("/admin", get(routes_admin::serve_admin_html))
        .route("/v1/health", get(health_check))
        .route("/v1/health/deep", get(routes_admin::deep_health_check))
        .route("/v1/health/cache", get(routes_admin::admin_caches_report))
        .route("/v1/images/proxy", get(routes_images::proxy_cover_image))
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
        .route("/v1/novels/import", post(routes_import::import_novel_flexible))
        .route("/v1/novels/updates/check-batch", post(routes_extra::check_updates_batch))
        .route("/v1/novels/:novel_id", get(routes_novels::get_novel_detail))
        .route("/v1/novels/:novel_id/check-updates", post(routes_extra::check_novel_updates))
        .route("/v1/novels/:novel_id/chapters/:chapter_number", get(routes_novels::get_chapter))
        .route("/v1/novels/:novel_id/chapters/:chapter_number/recrawl", post(routes_translate::recrawl_chapter))
        .route("/v1/novels/:novel_id/chapters/:chapter_number/recap", get(routes_novels::get_chapter_recap))
        .route("/v1/novels/:novel_id/export/epub", get(routes_novels::export_novel_epub))
        .route("/v1/novels/:novel_id/export.epub", get(routes_novels::export_novel_epub))
        .route("/v1/novels/:novel_id/glossary", get(routes_extra::get_glossary).put(routes_extra::put_glossary))
        // Cloud Sync, Bookmarks, Vocabulary & OTA Update routes
        .route("/v1/progress", get(routes_sync::get_all_progress).post(routes_sync::sync_progress))
        .route("/v1/progress/sync", post(routes_sync::sync_progress))
        .route("/v1/progress/:novel_id", get(routes_sync::get_one_progress))
        .route("/v1/bookmarks", get(routes_sync::pull_bookmarks).post(routes_sync::sync_bookmarks))
        .route("/v1/bookmarks/sync", post(routes_sync::sync_bookmarks))
        .route("/v1/bookmarks/:bookmark_id", delete(routes_sync::delete_bookmark))
        .route("/v1/vocabulary/sync", get(routes_sync::pull_vocabulary).post(routes_sync::push_vocabulary))
        .route("/v1/app/version", get(routes_sync::check_app_update).post(routes_admin::admin_publish_app_version))
        .route("/v1/app/check-update", get(routes_sync::check_app_update))
        .route("/v1/app/download-latest", get(routes_import::download_latest_apk))
        // AI Translation, VieNeu-TTS & Scraper routes
        .route("/v1/translate", post(routes_translate::translate_chapter))
        .route("/v1/translate/batch", post(routes_extra::batch_translate))
        .route("/v1/batch-translate", post(routes_extra::batch_translate))
        .route("/v1/jobs/:job_id", get(routes_extra::get_job_status))
        .route("/v1/overnight/status", get(routes_extra::get_overnight_status))
        .route("/v1/overnight/:novel_id/stop", post(routes_extra::stop_overnight))
        .route("/v1/tts/voices", get(routes_ai_tts::list_voices))
        .route("/v1/tts/status", get(routes_ai_tts::tts_status))
        .route("/v1/tts/stream", get(routes_ai_tts::stream_tts).post(routes_ai_tts::stream_tts))
        .route("/v1/tts/prefetch", post(routes_ai_tts::prefetch_tts))
        .route("/v1/tts/prerender", post(routes_ai_tts::prerender_tts))
        .route("/v1/scrape", post(scraper::scrape_url))
        // Settings, Lore, Companion & English Tutor routes
        .route("/v1/settings", get(routes_extra::get_settings))
        .route("/v1/settings/models", get(routes_extra::get_models))
        .route("/v1/settings/models/refresh", post(routes_extra::get_models))
        .route("/v1/settings/models/select", post(routes_extra::select_model))
        .route("/v1/settings/profile", get(routes_extra::get_profile).post(routes_extra::update_profile))
        .route("/v1/settings/api-key", post(routes_extra::update_api_key))
        .route("/v1/lore/explain", post(routes_extra::explain_lore))
        .route("/v1/companion/chat", post(routes_extra::companion_chat))
        .route("/v1/companion/chat/stream", post(routes_extra::companion_chat_stream))
        .route("/v1/english/explain-word", post(routes_english::explain_word))
        .route("/v1/english/explain-words-batch", post(routes_english::explain_words_batch))
        .route("/v1/english/translate-sentence", post(routes_english::translate_sentence))
        .route("/v1/english/adapt-chapter", post(routes_english::adapt_chapter))
        .route("/v1/english/life-scenarios", post(routes_english::life_scenarios))
        .route("/v1/english/evaluate-sentence", post(routes_english::evaluate_sentence))
        .route("/v1/english/practice-quiz", post(routes_english::practice_quiz))
        .route("/v1/english/generate-flashcard-art", post(routes_english::generate_flashcard_art))
        .route("/v1/english/weave-context-story", post(routes_english::weave_context_story))
        .route("/v1/english/grammar-stats", get(routes_english::grammar_stats))
        .route("/v1/english/grammar-patterns", post(routes_english::grammar_patterns))
        .route("/v1/english/progressive-chapter", post(routes_english::progressive_chapter))
        .route("/v1/english/comprehension-questions", post(routes_english::comprehension_questions))
        .route("/v1/english/word-graph", get(routes_english::word_graph).post(routes_english::word_graph))
        .route("/v1/english/pronunciation/evaluate", post(routes_english::evaluate_pronunciation))
        .merge(admin_api)
        .nest_service("/static", static_service)
        .nest_service("/flashcards", flashcards_service)
        .layer(DefaultBodyLimit::max(150 * 1024 * 1024))
        .layer(CorsLayer::permissive())
        .with_state(state)
}

async fn health_check() -> Json<serde_json::Value> {
    Json(json!({
        "status": "healthy",
        "service": "TruyenCloud Rust Backend (Axum + Tokio + SQLite)",
        "version": "1.6.1-rust",
        "runtime": "rust-native",
        "engine": "Rust Axum + Antigravity CLI + VieNeu-TTS + FSRS Core"
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
