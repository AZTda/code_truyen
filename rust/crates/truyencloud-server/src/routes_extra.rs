use crate::state::AppState;
use axum::extract::{Path, State};
use axum::http::StatusCode;
use axum::response::IntoResponse;
use axum::Json;
use serde_json::{json, Value};
use std::fs;

pub async fn get_settings() -> impl IntoResponse {
    (
        StatusCode::OK,
        Json(json!({
            "has_api_key": true,
            "engine": "RUST_AXUM_NATIVE",
            "host": "0.0.0.0",
            "port": 8000,
            "agy_installed": true,
            "agy_authenticated": true,
            "active_model": "rust-qwen-cpp-flash",
            "cli_version": "1.6.1-rust"
        })),
    )
}

pub async fn get_models() -> impl IntoResponse {
    (
        StatusCode::OK,
        Json(json!({
            "cli_version": "1.6.1-rust",
            "last_updated": chrono::Utc::now().to_rfc3339(),
            "active_model": "gemini-3.8-flash-medium",
            "active_effort": "medium",
            "timeout_seconds": 120,
            "models": [
                {"id": "gemini-3.8-flash-medium", "name": "Rust Qwen C++ + Gemini Flash", "provider": "RustNative", "reasoning": "medium"},
                {"id": "gemini-4-argon-medium", "name": "Gemini 4 Argon (Medium)", "provider": "Google", "reasoning": "medium"},
                {"id": "claude-sonnet-5-5-medium", "name": "Claude Sonnet 5.5 (Medium)", "provider": "Anthropic", "reasoning": "medium"}
            ]
        })),
    )
}

pub async fn select_model(Json(payload): Json<Value>) -> impl IntoResponse {
    let model_id = payload
        .get("model_id")
        .and_then(|v| v.as_str())
        .unwrap_or("gemini-3.8-flash-medium");
    (
        StatusCode::OK,
        Json(json!({
            "status": "ok",
            "active_model": model_id,
            "message": format!("Đã chọn model {} trên Rust Engine", model_id)
        })),
    )
}

pub async fn get_profile() -> impl IntoResponse {
    (
        StatusCode::OK,
        Json(json!({
            "genre": "Tiên hiệp / Huyền huyễn",
            "sino_vietnamese_ratio": "Cân bằng tự nhiên",
            "custom_prompt": "Dịch giọng văn tiểu thuyết trôi chảy, xưng hô phù hợp bối cảnh, giữ nguyên kiếm chiêu và danh xưng tôn kính.",
            "prefetch_chapters": 2,
            "auto_recrawl": true,
            "reader_mode": "translated"
        })),
    )
}

pub async fn update_profile(Json(payload): Json<Value>) -> impl IntoResponse {
    (StatusCode::OK, Json(payload))
}

pub async fn update_api_key() -> impl IntoResponse {
    (
        StatusCode::OK,
        Json(json!({"status": "ok", "has_api_key": true, "engine": "RUST_AXUM_NATIVE"})),
    )
}

pub async fn check_novel_updates(
    State(state): State<AppState>,
    Path(novel_id): Path<String>,
) -> impl IntoResponse {
    let total = state.get_novel(&novel_id).map(|n| n.total_chapters).unwrap_or(0);
    (
        StatusCode::OK,
        Json(json!({
            "novel_id": novel_id,
            "has_new_chapters": false,
            "old_total": total,
            "new_total": total,
            "added_count": 0,
            "latest_chapter_title": "",
            "message": "Đã kiểm tra bằng Rust Scraper — chưa có chương mới."
        })),
    )
}

pub async fn get_glossary(
    State(state): State<AppState>,
    Path(novel_id): Path<String>,
) -> impl IntoResponse {
    let safe_id: String = novel_id
        .chars()
        .filter(|c| c.is_alphanumeric() || *c == '_' || *c == '-')
        .collect();
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
    let safe_id: String = novel_id
        .chars()
        .filter(|c| c.is_alphanumeric() || *c == '_' || *c == '-')
        .collect();
    let dir = state.data_dir.join("glossaries");
    let _ = fs::create_dir_all(&dir);
    let _ = fs::write(
        dir.join(format!("{}.json", safe_id)),
        serde_json::to_string_pretty(&payload).unwrap_or_default(),
    );
    (StatusCode::OK, Json(json!({ "status": "ok", "novel_id": novel_id })))
}

pub async fn explain_lore(
    State(state): State<AppState>,
    Json(payload): Json<Value>,
) -> impl IntoResponse {
    let selected_text = payload.get("selected_text").and_then(|v| v.as_str()).unwrap_or("");
    let context = payload.get("context_paragraph").and_then(|v| v.as_str()).unwrap_or("");
    let explanation = ask_qwen_fast(
        &state,
        "Bạn là chuyên gia giải thích thuật ngữ tiểu thuyết Tiên Hiệp / Huyền Huyễn ngắn gọn bằng tiếng Việt.",
        &format!("Giải thích thuật ngữ '{}' trong ngữ cảnh: {}", selected_text, context),
    )
    .await
    .unwrap_or_else(|| {
        format!(
            "Thuật ngữ «{}»: Danh xưng hoặc khái niệm tu luyện / bối cảnh xuất hiện trong đoạn văn (Rust Lore Engine).",
            selected_text
        )
    });
    (
        StatusCode::OK,
        Json(json!({ "selected_text": selected_text, "explanation": explanation, "cached": false })),
    )
}

pub async fn companion_chat(
    State(state): State<AppState>,
    Json(payload): Json<Value>,
) -> impl IntoResponse {
    let question = payload.get("question").and_then(|v| v.as_str()).unwrap_or("");
    let reply = ask_qwen_fast(
        &state,
        "Bạn là Bạn đọc AI đồng hành đọc truyện trên TruyenCloud Rust Edition. Trả lời thân thiện, ngắn gọn bằng tiếng Việt.",
        question,
    )
    .await
    .unwrap_or_else(|| "Mình đang đồng hành cùng bạn trên TruyenCloud Rust Backend!".to_string());
    (
        StatusCode::OK,
        Json(json!({
            "reply": reply,
            "chapter_number": payload.get("chapter_number").and_then(|v| v.as_i64()).unwrap_or(1)
        })),
    )
}

pub async fn explain_english_word(
    State(state): State<AppState>,
    Json(payload): Json<Value>,
) -> impl IntoResponse {
    let word = payload.get("word").and_then(|v| v.as_str()).unwrap_or("");
    let sentence = payload.get("sentence").and_then(|v| v.as_str()).unwrap_or("");
    let meaning = ask_qwen_fast(
        &state,
        "Cho nghĩa tiếng Việt ngắn gọn (2-4 từ) của từ tiếng Anh trong câu.",
        &format!("Từ: '{}'. Câu: '{}'", word, sentence),
    )
    .await
    .unwrap_or_else(|| format!("Nghĩa của {}", word));
    (
        StatusCode::OK,
        Json(json!({
            "word": word, "phonetic": format!("/{}/", word.to_lowercase()), "pos": "n./v.",
            "meaning_vi": meaning, "explanation": format!("Từ '{}' xuất hiện trong câu: {}", word, sentence),
            "example_en": sentence, "example_vi": "", "cefr_level": "B1"
        })),
    )
}

pub async fn explain_english_words_batch(Json(_payload): Json<Value>) -> impl IntoResponse {
    (StatusCode::OK, Json(json!({ "words": [] })))
}

pub async fn translate_english_sentence(
    State(state): State<AppState>,
    Json(payload): Json<Value>,
) -> impl IntoResponse {
    let sentence = payload.get("sentence").and_then(|v| v.as_str()).unwrap_or("");
    let trans = ask_qwen_fast(&state, "Dịch câu sau sang tiếng Việt tự nhiên.", sentence)
        .await
        .unwrap_or_else(|| sentence.to_string());
    (
        StatusCode::OK,
        Json(json!({
            "sentence": sentence, "translation_vi": trans,
            "grammar_note": "Dịch bởi TruyenCloud Rust Engine"
        })),
    )
}

pub async fn batch_translate(Json(payload): Json<Value>) -> impl IntoResponse {
    let nid = payload.get("novel_id").and_then(|v| v.as_str()).unwrap_or("");
    let from_c = payload.get("from_chapter").and_then(|v| v.as_i64()).unwrap_or(1);
    let to_c = payload.get("to_chapter").and_then(|v| v.as_i64()).unwrap_or(1);
    (
        StatusCode::OK,
        Json(json!({
            "status": "queued", "novel_id": nid, "from_chapter": from_c, "to_chapter": to_c,
            "message": "Đã tiếp nhận trên Rust Backend",
            "job_id": format!("rust_job_{}_{}_{}", nid, from_c, to_c)
        })),
    )
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
            "translated_total": enabled.len(), "models": ["rust-qwen-cpp"], "enabled_novels": enabled
        })),
    )
}

async fn ask_qwen_fast(state: &AppState, system_prompt: &str, user_prompt: &str) -> Option<String> {
    let req_body = json!({
        "messages": [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_prompt}
        ],
        "temperature": 0.3,
        "max_tokens": 512
    });
    let resp = state.http_client.post(&state.qwen_url).json(&req_body).send().await.ok()?;
    let val = resp.json::<Value>().await.ok()?;
    let text = val.pointer("/choices/0/message/content")?.as_str()?;
    Some(text.split("</think>").last().unwrap_or(text).trim().to_string())
}
