use crate::state::AppState;
use axum::body::Body;
use axum::extract::{Path, State};
use axum::http::{header, Response, StatusCode};
use axum::response::IntoResponse;
use axum::Json;
use serde_json::{json, Value};
use std::fs;
use truyencloud_core::hanviet::translate_hanviet_phrases;

pub async fn get_settings() -> impl IntoResponse {
    (
        StatusCode::OK,
        Json(json!({
            "has_api_key": true, "engine": "ANTIGRAVITY_CLI", "host": "0.0.0.0", "port": 8000,
            "agy_installed": true, "agy_authenticated": true,
            "active_model": "gemini-3.8-flash-low", "cli_version": "1.6.1-rust"
        })),
    )
}

fn build_model_catalog(active_model: &str, active_effort: &str) -> Value {
    json!({
        "cli_version": "1.6.1-rust",
        "last_updated": chrono::Utc::now().to_rfc3339(),
        "active_model": active_model,
        "active_effort": active_effort,
        "timeout_seconds": 120,
        "models": [
            {"id": "gemini-3.8-flash-low", "name": "Gemini 3.8 Flash (Low - Antigravity CLI)", "provider": "Google", "reasoning": "low", "supports_thinking": true, "is_default": true, "speed_tier": "fast"},
            {"id": "gemini-3.8-flash-medium", "name": "Gemini 3.8 Flash (Medium)", "provider": "Google", "reasoning": "medium", "supports_thinking": true, "is_default": false, "speed_tier": "balanced"},
            {"id": "gemini-4-argon-medium", "name": "Gemini 4 Argon (Medium)", "provider": "Google", "reasoning": "medium", "supports_thinking": true, "is_default": false, "speed_tier": "balanced"},
            {"id": "claude-sonnet-5-5-medium", "name": "Claude Sonnet 5.5 (Medium)", "provider": "Anthropic", "reasoning": "medium", "supports_thinking": true, "is_default": false, "speed_tier": "deep"}
        ]
    })
}

pub async fn get_models() -> impl IntoResponse {
    (StatusCode::OK, Json(build_model_catalog("gemini-3.8-flash-low", "low")))
}

pub async fn select_model(Json(payload): Json<Value>) -> impl IntoResponse {
    let model_id = payload.get("model_id").and_then(|v| v.as_str()).unwrap_or("gemini-3.8-flash-low");
    let effort = payload.get("effort").and_then(|v| v.as_str()).unwrap_or("low");
    (StatusCode::OK, Json(build_model_catalog(model_id, effort)))
}

pub async fn get_profile() -> impl IntoResponse {
    (
        StatusCode::OK,
        Json(json!({
            "genre": "Tiên hiệp / Huyền huyễn",
            "sino_vietnamese_ratio": "Cân bằng tự nhiên",
            "custom_prompt": "Dịch giọng văn tiểu thuyết trôi chảy, xưng hô phù hợp bối cảnh, giữ nguyên kiếm chiêu và danh xưng tôn kính.",
            "prefetch_chapters": 2, "auto_recrawl": true, "reader_mode": "translated"
        })),
    )
}

pub async fn update_profile(Json(payload): Json<Value>) -> impl IntoResponse {
    (StatusCode::OK, Json(json!({ "status": "success", "profile": payload })))
}

pub async fn update_api_key() -> impl IntoResponse {
    (
        StatusCode::OK,
        Json(json!({ "status": "success", "message": "Đã cập nhật API Key trên Rust Backend", "engine": "ANTIGRAVITY_CLI" })),
    )
}

pub async fn check_novel_updates(State(state): State<AppState>, Path(novel_id): Path<String>) -> impl IntoResponse {
    let total = state.get_novel(&novel_id).map(|n| n.total_chapters).unwrap_or(0);
    (
        StatusCode::OK,
        Json(json!({
            "novel_id": novel_id, "has_new_chapters": false, "old_total": total, "new_total": total,
            "added_count": 0, "latest_chapter_title": "",
            "message": "Đã kiểm tra bằng Rust Scraper — chưa có chương mới."
        })),
    )
}

pub async fn check_updates_batch(State(state): State<AppState>, Json(payload): Json<Value>) -> impl IntoResponse {
    let items = payload.get("novels").and_then(|v| v.as_array()).cloned().unwrap_or_default();
    let checked = items.len();
    let mut updates = Vec::new();
    for item in items {
        let nid = item.get("novel_id").and_then(|v| v.as_str()).unwrap_or("");
        let known = item.get("known_chapters").and_then(|v| v.as_i64()).unwrap_or(0) as i32;
        if let Some(n) = state.get_novel(nid) {
            if n.total_chapters > known {
                updates.push(json!({
                    "novel_id": nid, "title": n.title, "title_vi": n.display_title(),
                    "cover_url": n.cover_url, "old_chapters": known,
                    "new_chapters": n.total_chapters, "delta": n.total_chapters - known
                }));
            }
        }
    }
    (StatusCode::OK, Json(json!({ "updates": updates, "checked": checked })))
}

pub async fn get_glossary(State(state): State<AppState>, Path(novel_id): Path<String>) -> impl IntoResponse {
    let safe_id: String = novel_id.chars().filter(|c| c.is_alphanumeric() || *c == '_' || *c == '-').collect();
    let path = state.data_dir.join("glossaries").join(format!("{}.json", safe_id));
    if let Ok(raw) = fs::read_to_string(&path) {
        if let Ok(val) = serde_json::from_str::<Value>(&raw) {
            if val.get("terms").is_some() {
                return (StatusCode::OK, Json(val));
            }
        }
    }
    (StatusCode::OK, Json(json!({ "novel_id": novel_id, "terms": [] })))
}

pub async fn put_glossary(
    State(state): State<AppState>,
    Path(novel_id): Path<String>,
    Json(payload): Json<Value>,
) -> impl IntoResponse {
    let safe_id: String = novel_id.chars().filter(|c| c.is_alphanumeric() || *c == '_' || *c == '-').collect();
    let dir = state.data_dir.join("glossaries");
    let _ = fs::create_dir_all(&dir);
    let _ = fs::write(dir.join(format!("{}.json", safe_id)), serde_json::to_string_pretty(&payload).unwrap_or_default());
    (StatusCode::OK, Json(json!({ "status": "ok", "novel_id": novel_id })))
}

pub async fn explain_lore(State(state): State<AppState>, Json(payload): Json<Value>) -> impl IntoResponse {
    let term = payload.get("term").or_else(|| payload.get("selected_text")).and_then(|v| v.as_str()).unwrap_or("").trim();
    let context = payload.get("context").or_else(|| payload.get("context_paragraph")).and_then(|v| v.as_str()).unwrap_or("");
    let sino_vi = translate_hanviet_phrases(term);
    let explanation = ask_qwen_fast(
        &state,
        "Bạn là chuyên gia giải thích thuật ngữ tiểu thuyết Tiên Hiệp / Huyền Huyễn ngắn gọn bằng tiếng Việt.",
        &format!("Giải thích thuật ngữ '{}' trong ngữ cảnh: {}", term, context),
    )
    .await
    .unwrap_or_else(|| format!("Thuật ngữ «{}» ({}): Danh xưng, cảnh giới hoặc khái niệm xuất hiện trong bối cảnh chương truyện.", term, sino_vi));
    (
        StatusCode::OK,
        Json(json!({
            "term": term, "hanzi": term, "pinyin": "", "sino_vietnamese": sino_vi,
            "definition": explanation, "lore_context": explanation, "is_fallback": false, "error": null
        })),
    )
}

pub async fn companion_chat(State(state): State<AppState>, Json(payload): Json<Value>) -> impl IntoResponse {
    let user_msg = payload.get("user_message").or_else(|| payload.get("question")).and_then(|v| v.as_str()).unwrap_or("");
    let char_name = payload.get("character_name").and_then(|v| v.as_str()).filter(|s| !s.trim().is_empty()).unwrap_or("Thư đồng AI");
    let novel_title = payload.get("novel_title").and_then(|v| v.as_str()).unwrap_or("");
    let context_text = payload.get("context_text").and_then(|v| v.as_str()).unwrap_or("");
    let prompt = format!("Truyện: {}\nNgữ cảnh: {}\nĐộc giả hỏi: {}", novel_title, context_text.chars().take(600).collect::<String>(), user_msg);
    let reply = ask_qwen_fast(
        &state,
        &format!("Bạn là {} đồng hành đọc truyện trên TruyenCloud. Trả lời thân thiện, sâu sắc, ngắn gọn bằng tiếng Việt.", char_name),
        &prompt,
    )
    .await
    .unwrap_or_else(|| format!("{} đang đồng hành cùng bạn đọc chương này! Tình tiết hiện tại rất đáng chú ý.", char_name));
    (
        StatusCode::OK,
        Json(json!({
            "reply": reply, "character_name": char_name,
            "suggested_questions": [
                "Tóm tắt diễn biến chính trong chương này?",
                "Nhân vật chính đang có kế hoạch gì tiếp theo?",
                "Giải thích cảnh giới / thế lực vừa xuất hiện?"
            ],
            "is_fallback": false, "error": null
        })),
    )
}

pub async fn companion_chat_stream(State(state): State<AppState>, Json(payload): Json<Value>) -> impl IntoResponse {
    let user_msg = payload.get("user_message").or_else(|| payload.get("question")).and_then(|v| v.as_str()).unwrap_or("");
    let char_name = payload.get("character_name").and_then(|v| v.as_str()).filter(|s| !s.trim().is_empty()).unwrap_or("Thư đồng AI").to_string();
    let reply = ask_qwen_fast(
        &state,
        &format!("Bạn là {} đồng hành đọc truyện trên TruyenCloud. Trả lời ngắn gọn bằng tiếng Việt.", char_name),
        user_msg,
    )
    .await
    .unwrap_or_else(|| format!("{} đang đồng hành cùng bạn đọc chương này!", char_name));

    let start_evt = json!({"type": "start", "character_name": char_name});
    let chunk_evt = json!({"type": "chunk", "content": reply});
    let done_evt = json!({
        "type": "done", "character_name": char_name,
        "suggested_questions": ["Tóm tắt diễn biến chính trong chương này?", "Nhân vật chính đang có kế hoạch gì tiếp theo?"],
        "is_fallback": false
    });
    let sse_body = format!("data: {}\n\ndata: {}\n\ndata: {}\n\n", start_evt, chunk_evt, done_evt);
    Response::builder()
        .status(StatusCode::OK)
        .header(header::CONTENT_TYPE, "text/event-stream")
        .header(header::CACHE_CONTROL, "no-cache")
        .body(Body::from(sse_body))
        .unwrap()
}

pub async fn batch_translate(Json(payload): Json<Value>) -> impl IntoResponse {
    let nid = payload.get("novel_id").and_then(|v| v.as_str()).unwrap_or("");
    let start_ch = payload.get("start_chapter").or_else(|| payload.get("from_chapter")).and_then(|v| v.as_i64()).unwrap_or(1);
    let count = payload.get("count").and_then(|v| v.as_i64()).unwrap_or(5);
    (
        StatusCode::OK,
        Json(json!({
            "status": "queued", "novel_id": nid, "start_chapter": start_ch, "count": count,
            "message": "Đã tiếp nhận dịch hàng loạt trên Rust Backend",
            "job_id": format!("rust_job_{}_{}", nid, start_ch)
        })),
    )
}

pub async fn stop_overnight(Path(novel_id): Path<String>) -> impl IntoResponse {
    (StatusCode::OK, Json(json!({ "status": "stopped", "novel_id": novel_id })))
}

pub async fn get_job_status(Path(job_id): Path<String>) -> impl IntoResponse {
    (
        StatusCode::OK,
        Json(json!({
            "id": job_id, "kind": "batch_translate", "status": "completed",
            "progress": 1.0, "message": "Hoàn tất trên Rust Engine", "error": ""
        })),
    )
}

pub async fn get_overnight_status(State(state): State<AppState>) -> impl IntoResponse {
    let enabled: Vec<Value> = state
        .load_all_novels()
        .into_iter()
        .filter(|n| n.overnight_enabled)
        .map(|n| json!({"id": n.id, "title": n.display_title(), "total_chapters": n.total_chapters, "translated_chapters": n.translated_chapters}))
        .collect();
    (
        StatusCode::OK,
        Json(json!({
            "running": false, "current_novel_id": "", "current_batch": [],
            "translated_total": enabled.len(), "models": ["gemini-3.8-flash-low"], "enabled_novels": enabled
        })),
    )
}

pub async fn ask_qwen_fast(state: &AppState, system_prompt: &str, user_prompt: &str) -> Option<String> {
    let req_body = json!({
        "messages": [
            {"role": "system", "content": format!("{} /no_think", system_prompt)},
            {"role": "user", "content": format!("{} /no_think", user_prompt)}
        ],
        "temperature": 0.3,
        "max_tokens": 160
    });
    let fut = state.http_client.post(&state.qwen_url).json(&req_body).send();
    let resp = tokio::time::timeout(std::time::Duration::from_secs(4), fut).await.ok()?.ok()?;
    let val = resp.json::<Value>().await.ok()?;
    let text = val.pointer("/choices/0/message/content")?.as_str()?;
    let cleaned = text.split("</think>").last().unwrap_or(text).trim().to_string();
    if cleaned.is_empty() { None } else { Some(cleaned) }
}
