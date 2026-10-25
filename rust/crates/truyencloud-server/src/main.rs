mod routes_admin;
mod routes_ai_tts;
mod routes_auth;
mod routes_novels;
mod routes_sync;
mod scraper;
mod state;

use axum::routing::{get, post};
use axum::{Json, Router};
use serde_json::json;
use state::AppState;
use std::net::SocketAddr;
use std::path::PathBuf;
use tower_http::cors::CorsLayer;
use tower_http::services::ServeDir;

pub fn build_router(state: AppState) -> Router {
    let static_service = ServeDir::new(state.static_dir.clone());

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
        .route("/v1/novels/:novel_id", get(routes_novels::get_novel_detail))
        .route(
            "/v1/novels/:novel_id/chapters/:chapter_number",
            get(routes_novels::get_chapter),
        )
        .route(
            "/v1/novels/:novel_id/export/epub",
            get(routes_novels::export_novel_epub),
        )
        .route(
            "/v1/novels/:novel_id/glossary",
            get(routes_novels::get_glossary).put(routes_novels::put_glossary),
        )
        // Cloud Sync & OTA Update routes
        .route(
            "/v1/progress",
            get(routes_sync::get_progress).post(routes_sync::post_progress),
        )
        .route("/v1/vocabulary/sync", post(routes_sync::sync_vocabulary))
        .route("/v1/app/check-update", get(routes_sync::check_app_update))
        // AI Translation, VieNeu-TTS & Scraper routes
        .route("/v1/translate", post(routes_ai_tts::translate_text))
        .route("/v1/tts/voices", get(routes_ai_tts::list_voices))
        .route("/v1/tts/stream", get(routes_ai_tts::stream_tts))
        .route("/v1/tts/prerender", post(routes_ai_tts::prerender_tts))
        .route("/v1/scrape", post(scraper::scrape_url))
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
        .layer(CorsLayer::permissive())
        .with_state(state)
}

async fn health_check() -> Json<serde_json::Value> {
    Json(json!({
        "status": "healthy",
        "service": "TruyenCloud Rust Backend (Axum + Tokio + SQLite)",
        "version": "1.6.0",
        "runtime": "rust-native",
        "engine": "Antigravity + VieNeu-TTS + FSRS Core"
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

    // Tự động trỏ tới thư mục `../server` (để dùng chung dữ liệu `truyencloud.db` và `static/` nếu có)
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
        .unwrap_or(8081);

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
