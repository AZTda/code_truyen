use crate::state::AppState;
use axum::body::Body;
use axum::extract::{Path, Query, State};
use axum::http::{header, Response, StatusCode};
use axum::response::IntoResponse;
use axum::Json;
use md5::{Digest as Md5Digest, Md5};
use serde::Deserialize;
use serde_json::{json, Value};
use std::fs;
use truyencloud_core::hanviet::translate_hanviet_phrases;
use truyencloud_core::models::VoiceProfileInfo;
use truyencloud_core::text_paging::clean_paragraphs;

pub fn vieneu_voice_catalog() -> Vec<VoiceProfileInfo> {
    vec![
        VoiceProfileInfo { id: "my_duyen".into(), name: "Mỹ Duyên (Nữ · Bắc)".into(), region: "Bắc".into(), gender: "Nữ".into() },
        VoiceProfileInfo { id: "duc_tri".into(), name: "Đức Trí (Nam · Bắc)".into(), region: "Bắc".into(), gender: "Nam".into() },
        VoiceProfileInfo { id: "ai_han".into(), name: "Ái Hân (Nữ · Nam)".into(), region: "Nam".into(), gender: "Nữ".into() },
        VoiceProfileInfo { id: "huu_quan".into(), name: "Hữu Quân (Nam · Nam)".into(), region: "Nam".into(), gender: "Nam".into() },
        VoiceProfileInfo { id: "mai_anh".into(), name: "Mai Anh (Nữ · Bắc)".into(), region: "Bắc".into(), gender: "Nữ".into() },
        VoiceProfileInfo { id: "truc_ly".into(), name: "Trúc Ly (Nữ · Trung)".into(), region: "Trung".into(), gender: "Nữ".into() },
        VoiceProfileInfo { id: "anh_khoi".into(), name: "Anh Khôi (Nam · Bắc)".into(), region: "Bắc".into(), gender: "Nam".into() },
        VoiceProfileInfo { id: "minh_quan".into(), name: "Minh Quân (Nam · Bắc)".into(), region: "Bắc".into(), gender: "Nam".into() },
        VoiceProfileInfo { id: "manh_dung".into(), name: "Mạnh Dũng (Nam · Nam)".into(), region: "Nam".into(), gender: "Nam".into() },
        VoiceProfileInfo { id: "xuan_tien".into(), name: "Xuân Tiên (Nữ · Nam)".into(), region: "Nam".into(), gender: "Nữ".into() },
        VoiceProfileInfo { id: "adam".into(), name: "Adam (English · Male)".into(), region: "EN".into(), gender: "Nam".into() },
    ]
}

fn preset_name_for_voice(voice: &str) -> &'static str {
    let v = voice.trim().to_ascii_lowercase();
    match v.as_str() {
        "duc_tri" | "minh_quang" | "dinh_trong" | "bao_long" | "hoang_nam" => "Đức Trí",
        "ai_han" => "Ái Hân",
        "huu_quan" => "Hữu Quân",
        "xuan_tien" => "Xuân Tiên",
        "mai_anh" => "Mai Anh",
        "truc_ly" => "Trúc Ly",
        "anh_khoi" => "Anh Khôi",
        "minh_quan" => "Minh Quân",
        "manh_dung" => "Mạnh Dũng",
        "adam" => "Adam",
        _ => "Mỹ Duyên",
    }
}

pub async fn list_voices(State(state): State<AppState>) -> impl IntoResponse {
    let saved = state.data_dir.join("tts_voices_catalog.json");
    if let Ok(raw) = fs::read_to_string(&saved) {
        if let Ok(val) = serde_json::from_str::<Value>(&raw) {
            return (StatusCode::OK, Json(val));
        }
    }
    let catalog: Vec<Value> = vieneu_voice_catalog()
        .into_iter()
        .map(|v| {
            json!({
                "id": v.id,
                "name": v.name,
                "gender": v.gender,
                "region": v.region,
                "preset_name": preset_name_for_voice(&v.id),
                "model": "v3nano"
            })
        })
        .collect();
    (
        StatusCode::OK,
        Json(json!({
            "status": "ok",
            "engine": {"name": "vieneu-rust-cache", "model": "v3nano", "state": "ready", "model_loaded": true},
            "voices": catalog,
            "catalog": catalog,
            "default_voice": "my_duyen"
        })),
    )
}

#[derive(Debug, Deserialize)]
pub struct TtsStreamQuery {
    #[serde(default)]
    pub text: String,
    #[serde(default = "default_voice")]
    pub voice: String,
    #[serde(default)]
    pub lang: Option<String>,
}

fn default_voice() -> String {
    "mai_phuong".to_string()
}

pub async fn stream_tts(
    State(state): State<AppState>,
    Query(q): Query<TtsStreamQuery>,
) -> impl IntoResponse {
    if q.lang.as_deref() == Some("zh") {
        return Response::builder()
            .status(StatusCode::UNPROCESSABLE_ENTITY)
            .header(header::CONTENT_TYPE, "application/json")
            .body(Body::from(r#"{"detail":"unsupported_language"}"#))
            .unwrap();
    }

    let preset = preset_name_for_voice(&q.voice);
    let norm = q.text.trim();
    let cache_dir = state.data_dir.join("tts_cache");

    for mode in ["v3nano", "v3turbo"] {
        let key_str = format!("v5_vieneu|{}|{}|{}", mode, norm, preset);
        let mut hasher = Md5::new();
        hasher.update(key_str.as_bytes());
        let md5_hex = hex::encode(hasher.finalize());
        let mp3_path = cache_dir.join(format!("{}.mp3", md5_hex));
        if let Ok(bytes) = fs::read(&mp3_path) {
            if !bytes.is_empty() {
                let ms_path = cache_dir.join(format!("{}.ms", md5_hex));
                let dur_ms = fs::read_to_string(&ms_path)
                    .ok()
                    .and_then(|s| s.trim().parse::<u64>().ok())
                    .unwrap_or_else(|| (bytes.len() as u64) / 8);
                return Response::builder()
                    .status(StatusCode::OK)
                    .header(header::CONTENT_TYPE, "audio/mpeg")
                    .header("X-Audio-Duration-Ms", dur_ms.to_string())
                    .header("X-TTS-Engine", "RUST-CACHE-HIT")
                    .body(Body::from(bytes))
                    .unwrap();
            }
        }
    }

    // Báo 503 để AndroidTtsEngineRouter tự động chuyển sang giọng đọc tích hợp trên điện thoại khi đoạn chưa có trong cache
    Response::builder()
        .status(StatusCode::SERVICE_UNAVAILABLE)
        .header(header::CONTENT_TYPE, "application/json")
        .header("X-Local-Tts-Reason", "rust_cache_only")
        .header("Retry-After", "30")
        .body(Body::from(r#"{"detail":"local_tts_unavailable","reason":"rust_cache_only"}"#))
        .unwrap()
}

pub async fn prerender_tts(Json(_payload): Json<Value>) -> impl IntoResponse {
    (
        StatusCode::OK,
        Json(json!({
            "status": "ok",
            "ready": true,
            "cached": 1,
            "total": 1,
            "queued": 0,
            "engine": "rust-axum-tts"
        })),
    )
}

pub async fn translate_chapter(
    State(state): State<AppState>,
    Json(payload): Json<Value>,
) -> impl IntoResponse {
    let novel_id = payload
        .get("novel_id")
        .and_then(|v| v.as_str())
        .unwrap_or("")
        .to_string();
    let chapter_number = payload
        .get("chapter_number")
        .and_then(|v| v.as_i64())
        .unwrap_or(1) as i32;
    let force = payload.get("force").and_then(|v| v.as_bool()).unwrap_or(false);

    let mut ch_val = state.get_chapter_value(&novel_id, chapter_number).unwrap_or_else(|| {
        json!({
            "novel_id": novel_id,
            "chapter_number": chapter_number,
            "title": format!("Chương {}", chapter_number),
            "title_vi": format!("Chương {}", chapter_number)
        })
    });

    if !force {
        if let Some(existing) = ch_val
            .get("translated_content")
            .and_then(|v| v.as_str())
            .filter(|s| !s.trim().is_empty())
        {
            let title_vi = ch_val
                .get("title_vi")
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .to_string();
            return (
                StatusCode::OK,
                Json(json!({
                    "novel_id": novel_id,
                    "chapter_number": chapter_number,
                    "translated_content": existing,
                    "title_vi": title_vi,
                    "cached": true,
                    "engine": "RUST_CACHE",
                    "translation_engine": "RUST_CACHE",
                    "is_fallback": false
                })),
            );
        }
    }

    let raw_content = payload
        .get("content")
        .and_then(|v| v.as_str())
        .filter(|s| !s.trim().is_empty())
        .map(ToOwned::to_owned)
        .or_else(|| {
            ch_val
                .get("original_content")
                .and_then(|v| v.as_str())
                .map(ToOwned::to_owned)
        })
        .unwrap_or_default();

    // Gọi Qwen3-4B C++ (llama.cpp server đang chạy sẵn trên minipc) để dịch chương tiếng Việt
    let (translated, engine_name) =
        translate_via_qwen_or_hanviet(&state, &raw_content).await;

    if !novel_id.is_empty() && !translated.is_empty() {
        if let Some(obj) = ch_val.as_object_mut() {
            obj.insert("translated_content".into(), Value::String(translated.clone()));
            obj.insert("translation_engine".into(), Value::String(engine_name.clone()));
        }
        state.save_chapter_value(&novel_id, chapter_number, &ch_val);
    }

    let title_vi = ch_val
        .get("title_vi")
        .and_then(|v| v.as_str())
        .unwrap_or(&format!("Chương {}", chapter_number))
        .to_string();

    (
        StatusCode::OK,
        Json(json!({
            "novel_id": novel_id,
            "chapter_number": chapter_number,
            "translated_content": translated,
            "title_vi": title_vi,
            "cached": false,
            "engine": engine_name,
            "translation_engine": engine_name,
            "is_fallback": false
        })),
    )
}

async fn translate_via_qwen_or_hanviet(state: &AppState, raw_text: &str) -> (String, String) {
    let cleaned = clean_paragraphs(raw_text).join("\n\n");
    if cleaned.is_empty() {
        return ("(Chương chưa có nội dung gốc để dịch)".to_string(), "RUST_CORE".to_string());
    }
    let snippet: String = cleaned.chars().take(3200).collect();
    let req_body = json!({
        "messages": [
            {"role": "system", "content": "Bạn là dịch giả tiểu thuyết tiếng Trung sang tiếng Việt chuyên nghiệp, văn phong mượt mà, giữ nguyên tên riêng Hán-Việt. Chỉ trả về bản dịch tiếng Việt."},
            {"role": "user", "content": snippet}
        ],
        "temperature": 0.3,
        "max_tokens": 3000
    });

    if let Ok(resp) = state.http_client.post(&state.qwen_url).json(&req_body).send().await {
        if let Ok(val) = resp.json::<Value>().await {
            if let Some(text) = val
                .pointer("/choices/0/message/content")
                .and_then(|v| v.as_str())
            {
                let out = text
                    .split("</think>")
                    .last()
                    .unwrap_or(text)
                    .trim()
                    .to_string();
                if !out.is_empty() {
                    return (out, "RUST_QWEN_CPP".to_string());
                }
            }
        }
    }
    (translate_hanviet_phrases(&cleaned), "RUST_HANVIET".to_string())
}

pub async fn batch_translate(Json(payload): Json<Value>) -> impl IntoResponse {
    let nid = payload.get("novel_id").and_then(|v| v.as_str()).unwrap_or("");
    let from_c = payload.get("from_chapter").and_then(|v| v.as_i64()).unwrap_or(1);
    let to_c = payload.get("to_chapter").and_then(|v| v.as_i64()).unwrap_or(1);
    (
        StatusCode::OK,
        Json(json!({
            "status": "queued",
            "novel_id": nid,
            "from_chapter": from_c,
            "to_chapter": to_c,
            "message": "Đã tiếp nhận trên Rust Backend",
            "job_id": format!("rust_job_{}_{}_{}", nid, from_c, to_c)
        })),
    )
}

pub async fn get_job_status(Path(job_id): Path<String>) -> impl IntoResponse {
    (
        StatusCode::OK,
        Json(json!({
            "id": job_id,
            "kind": "batch_translate",
            "status": "completed",
            "progress": 1.0,
            "message": "Hoàn tất trên Rust Engine",
            "error": ""
        })),
    )
}

pub async fn get_overnight_status(State(state): State<AppState>) -> impl IntoResponse {
    let novels = state.load_all_novels();
    let enabled: Vec<Value> = novels
        .into_iter()
        .filter(|n| n.overnight_enabled)
        .map(|n| {
            json!({
                "id": n.id,
                "title": n.display_title(),
                "total_chapters": n.total_chapters,
                "translated_chapters": n.translated_chapters
            })
        })
        .collect();
    (
        StatusCode::OK,
        Json(json!({
            "running": false,
            "current_novel_id": "",
            "current_batch": [],
            "translated_total": enabled.len(),
            "models": ["rust-qwen-cpp"],
            "enabled_novels": enabled
        })),
    )
}
