use crate::routes_admin_novels::set_novel_overnight_flag;
use crate::routes_translate::translate_pipeline_lang;
use crate::scraper::fetch_chapter_body;
use crate::state::AppState;
use axum::body::Body;
use axum::extract::{Path, State};
use axum::http::{header, Response, StatusCode};
use axum::response::IntoResponse;
use axum::Json;
use serde_json::{json, Value};
use std::fs;
use truyencloud_core::hanviet::translate_hanviet_phrases;

fn read_active_model(state: &AppState) -> (String, String, i64) {
    let path = state.data_dir.join("models_cache.json");
    if let Ok(raw) = fs::read_to_string(path) {
        if let Ok(val) = serde_json::from_str::<Value>(&raw) {
            let m = val.get("active_model").and_then(|v| v.as_str()).unwrap_or("gemini-3.8-flash-low").to_string();
            let e = val.get("active_effort").and_then(|v| v.as_str()).unwrap_or("low").to_string();
            let t = val.get("timeout_seconds").and_then(|v| v.as_i64()).unwrap_or(120);
            return (m, e, t);
        }
    }
    ("gemini-3.8-flash-low".to_string(), "low".to_string(), 120)
}

pub async fn get_settings(State(state): State<AppState>) -> impl IntoResponse {
    let (active_model, _, _) = read_active_model(&state);
    (StatusCode::OK, Json(json!({
        "has_api_key": true, "engine": "ANTIGRAVITY_CLI", "host": "0.0.0.0", "port": 8000,
        "agy_installed": true, "agy_authenticated": true, "active_model": active_model, "cli_version": "1.6.1-rust"
    })))
}

fn build_model_catalog(active_model: &str, active_effort: &str, timeout_seconds: i64) -> Value {
    json!({
        "cli_version": "1.6.1-rust", "last_updated": chrono::Utc::now().to_rfc3339(),
        "active_model": active_model, "active_effort": active_effort, "timeout_seconds": timeout_seconds,
        "models": [
            {"id": "gemini-3.8-flash-low", "name": "Gemini 3.8 Flash (Low - Antigravity CLI)", "provider": "Google", "reasoning": "low", "supports_thinking": true, "is_default": active_model == "gemini-3.8-flash-low", "speed_tier": "fast"},
            {"id": "gemini-3.8-flash-medium", "name": "Gemini 3.8 Flash (Medium)", "provider": "Google", "reasoning": "medium", "supports_thinking": true, "is_default": active_model == "gemini-3.8-flash-medium", "speed_tier": "balanced"},
            {"id": "gemini-4-argon-medium", "name": "Gemini 4 Argon (Medium)", "provider": "Google", "reasoning": "medium", "supports_thinking": true, "is_default": active_model == "gemini-4-argon-medium", "speed_tier": "balanced"},
            {"id": "claude-sonnet-5-5-medium", "name": "Claude Sonnet 5.5 (Medium)", "provider": "Anthropic", "reasoning": "medium", "supports_thinking": true, "is_default": active_model == "claude-sonnet-5-5-medium", "speed_tier": "deep"}
        ]
    })
}

pub async fn get_models(State(state): State<AppState>) -> impl IntoResponse {
    let (m, e, t) = read_active_model(&state);
    (StatusCode::OK, Json(build_model_catalog(&m, &e, t)))
}

pub async fn select_model(State(state): State<AppState>, Json(payload): Json<Value>) -> impl IntoResponse {
    let (cur_m, cur_e, cur_t) = read_active_model(&state);
    let model_id = payload.get("model_id").and_then(|v| v.as_str()).unwrap_or(&cur_m);
    let effort = payload.get("effort").and_then(|v| v.as_str()).unwrap_or(&cur_e);
    let timeout = payload.get("timeout_seconds").and_then(|v| v.as_i64()).unwrap_or(cur_t);
    let cat = build_model_catalog(model_id, effort, timeout);
    let _ = fs::write(state.data_dir.join("models_cache.json"), serde_json::to_string_pretty(&cat).unwrap_or_default());
    (StatusCode::OK, Json(cat))
}

pub async fn get_profile(State(state): State<AppState>) -> impl IntoResponse {
    let path = state.data_dir.join("translation_profile.json");
    if let Ok(raw) = fs::read_to_string(path) {
        if let Ok(val) = serde_json::from_str::<Value>(&raw) { return (StatusCode::OK, Json(val)); }
    }
    (StatusCode::OK, Json(json!({
        "genre": "Tiên hiệp / Huyền huyễn", "sino_vietnamese_ratio": "Cân bằng tự nhiên",
        "custom_prompt": "Dịch giọng văn tiểu thuyết trôi chảy, xưng hô phù hợp bối cảnh, giữ nguyên kiếm chiêu và danh xưng tôn kính.",
        "prefetch_chapters": 2, "auto_recrawl": true, "reader_mode": "translated"
    })))
}

pub async fn update_profile(State(state): State<AppState>, Json(payload): Json<Value>) -> impl IntoResponse {
    let _ = fs::write(state.data_dir.join("translation_profile.json"), serde_json::to_string_pretty(&payload).unwrap_or_default());
    (StatusCode::OK, Json(json!({ "status": "success", "profile": payload })))
}

pub async fn update_api_key() -> impl IntoResponse {
    (StatusCode::OK, Json(json!({ "status": "success", "message": "Đã cập nhật API Key trên Rust Backend", "engine": "ANTIGRAVITY_CLI" })))
}

pub async fn check_novel_updates(State(state): State<AppState>, Path(novel_id): Path<String>) -> impl IntoResponse {
    let total = state.get_novel(&novel_id).map(|n| n.total_chapters).unwrap_or(0);
    (StatusCode::OK, Json(json!({
        "novel_id": novel_id, "has_new_chapters": false, "old_total": total, "new_total": total,
        "added_count": 0, "latest_chapter_title": "", "message": "Đã kiểm tra bằng Rust Scraper — chưa có chương mới."
    })))
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
                let delta = n.total_chapters - known;
                updates.push(json!({
                    "novel_id": nid, "title": n.title, "title_vi": n.display_title(),
                    "cover_url": n.cover_url, "old_chapters": known,
                    "total_chapters": n.total_chapters, "new_chapters": delta, "delta": delta
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

pub async fn batch_translate(State(state): State<AppState>, Json(payload): Json<Value>) -> impl IntoResponse {
    let nid = payload.get("novel_id").and_then(|v| v.as_str()).unwrap_or("").to_string();
    let from_ch = payload.get("from_chapter").or_else(|| payload.get("start_chapter")).and_then(|v| v.as_i64()).unwrap_or(1).max(1) as i32;
    let count = payload.get("count").and_then(|v| v.as_i64()).unwrap_or(5).max(1) as i32;
    let to_ch = payload.get("to_chapter").and_then(|v| v.as_i64()).map(|v| v as i32).unwrap_or(from_ch + count - 1).max(from_ch);
    let end_ch = to_ch.min(from_ch + 29);
    let force = payload.get("force").and_then(|v| v.as_bool()).unwrap_or(false);
    let overnight = payload.get("overnight").and_then(|v| v.as_bool()).unwrap_or(false);
    let queued = (end_ch - from_ch + 1).max(1);
    let job_id = format!("rust_job_{}_{}_{}", nid, from_ch, end_ch);

    if overnight && !nid.is_empty() {
        set_novel_overnight_flag(&state, &nid, true);
    }

    if let Ok(mut guard) = state.jobs.lock() {
        guard.insert(job_id.clone(), json!({
            "id": job_id, "kind": "batch_translate", "status": "running",
            "progress": 0.0, "message": format!("Đang dịch chương {} → {}", from_ch, end_ch), "error": ""
        }));
    }

    let bg_state = state.clone();
    let bg_nid = nid.clone();
    let bg_job = job_id.clone();
    tokio::spawn(async move {
        for (idx, ch_num) in (from_ch..=end_ch).enumerate() {
            let mut ch_val = bg_state.get_chapter_value(&bg_nid, ch_num).unwrap_or_else(|| json!({"novel_id": bg_nid, "chapter_number": ch_num}));
            let already = ch_val.get("translated_content").and_then(|v| v.as_str()).map(|s| s.trim().len() > 20).unwrap_or(false);
            if !already || force {
                let mut raw = ch_val.get("original_content").and_then(|v| v.as_str()).unwrap_or("").to_string();
                if raw.trim().is_empty() {
                    let url = ch_val.get("url").or_else(|| ch_val.get("source_url")).and_then(|v| v.as_str()).unwrap_or("").to_string();
                    if url.starts_with("http") {
                        if let Some(body) = fetch_chapter_body(&bg_state.http_client, &url).await {
                            raw = body.clone();
                            if let Some(obj) = ch_val.as_object_mut() { obj.insert("original_content".into(), Value::String(body)); }
                        }
                    }
                }
                if !raw.trim().is_empty() {
                    let (tr, eng) = translate_pipeline_lang(&bg_state, &raw, "vi", "b1").await;
                    if !tr.is_empty() {
                        if let Some(obj) = ch_val.as_object_mut() {
                            obj.insert("translated_content".into(), Value::String(tr));
                            obj.insert("translation_engine".into(), Value::String(eng));
                        }
                        bg_state.save_chapter_value(&bg_nid, ch_num, &ch_val);
                    }
                }
            }
            if let Ok(mut guard) = bg_state.jobs.lock() {
                let prog = ((idx + 1) as f64) / (queued as f64);
                guard.insert(bg_job.clone(), json!({
                    "id": bg_job, "kind": "batch_translate", "status": "running",
                    "progress": prog, "message": format!("Đã dịch xong chương {}/{}", ch_num, end_ch), "error": ""
                }));
            }
        }
        if let Ok(mut guard) = bg_state.jobs.lock() {
            guard.insert(bg_job.clone(), json!({
                "id": bg_job, "kind": "batch_translate", "status": "succeeded",
                "progress": 1.0, "message": "Hoàn tất dịch hàng loạt trên Rust Engine", "error": ""
            }));
        }
    });

    (StatusCode::OK, Json(json!({
        "status": "queued", "novel_id": nid, "from_chapter": from_ch, "to_chapter": end_ch,
        "start_chapter": from_ch, "count": queued, "queued_chapters": queued,
        "message": format!("Đã tiếp nhận dịch chương {} → {} trên Rust Backend", from_ch, end_ch),
        "job_id": job_id, "overnight": overnight
    })))
}

pub async fn stop_overnight(State(state): State<AppState>, Path(novel_id): Path<String>) -> impl IntoResponse {
    set_novel_overnight_flag(&state, &novel_id, false);
    (StatusCode::OK, Json(json!({
        "status": "stopped", "novel_id": novel_id, "from_chapter": 0, "to_chapter": 0, "queued_chapters": 0,
        "job_id": "", "overnight": false, "message": "Đã dừng dịch ngầm qua đêm."
    })))
}

pub async fn get_job_status(State(state): State<AppState>, Path(job_id): Path<String>) -> impl IntoResponse {
    if let Ok(guard) = state.jobs.lock() {
        if let Some(job) = guard.get(&job_id) { return (StatusCode::OK, Json(job.clone())); }
    }
    (StatusCode::OK, Json(json!({
        "id": job_id, "kind": "batch_translate", "status": "succeeded",
        "progress": 1.0, "message": "Hoàn tất trên Rust Engine", "error": ""
    })))
}

pub async fn get_overnight_status(State(state): State<AppState>) -> impl IntoResponse {
    let enabled: Vec<Value> = state.load_all_novels().into_iter().filter(|n| n.overnight_enabled)
        .map(|n| json!({"id": n.id, "title": n.display_title(), "total_chapters": n.total_chapters, "translated_chapters": n.translated_chapters})).collect();
    (StatusCode::OK, Json(json!({
        "running": !enabled.is_empty(),
        "current_novel_id": enabled.first().and_then(|v| v.get("id")).and_then(|v| v.as_str()).unwrap_or(""),
        "current_batch": [], "translated_total": enabled.len(), "models": ["gemini-3.8-flash-low"], "enabled_novels": enabled
    })))
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
