use crate::state::AppState;
use axum::body::Body;
use axum::extract::{Query, State};
use axum::http::{header, Response, StatusCode};
use axum::response::IntoResponse;
use axum::Json;
use md5::{Digest as Md5Digest, Md5};
use serde::Deserialize;
use serde_json::{json, Value};
use std::fs;
use truyencloud_core::models::VoiceProfileInfo;

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

fn preset_name_for_voice(voice_id: &str) -> &'static str {
    match voice_id.trim() {
        "my_duyen" | "mai_phuong" | "female" => "Mỹ Duyên",
        "duc_tri" | "minh_hoang" | "male" => "Đức Trí",
        "ai_han" | "lan_anh" => "Ái Hân",
        "huu_quan" | "quoc_bao" => "Hữu Quân",
        "mai_anh" | "thanh_ha" | "ngoc_linh" => "Mai Anh",
        "truc_ly" | "thao_vy" | "diu_hien" => "Trúc Ly",
        "anh_khoi" | "hoang_nam" => "Anh Khôi",
        "minh_quan" => "Minh Quân",
        "manh_dung" | "bao_long" => "Mạnh Dũng",
        "xuan_tien" => "Xuân Tiên",
        "adam" => "Adam",
        _ => "Mỹ Duyên",
    }
}

pub async fn list_voices(State(state): State<AppState>) -> impl IntoResponse {
    if let Ok(raw) = fs::read_to_string(state.data_dir.join("tts_voices_catalog.json")) {
        if let Ok(val) = serde_json::from_str::<Value>(&raw) {
            return (StatusCode::OK, Json(val));
        }
    }
    let catalog: Vec<Value> = vieneu_voice_catalog()
        .into_iter()
        .map(|v| json!({"id": v.id, "name": v.name, "gender": v.gender, "region": v.region, "preset_name": preset_name_for_voice(&v.id), "model": "v3nano"}))
        .collect();
    (
        StatusCode::OK,
        Json(json!({
            "status": "ok",
            "engine": {"name": "vieneu-rust", "model": "v3nano", "state": "ready", "model_loaded": true},
            "voices": catalog, "catalog": catalog, "default_voice": "my_duyen"
        })),
    )
}

pub async fn tts_status() -> impl IntoResponse {
    (
        StatusCode::OK,
        Json(json!({
            "engine": "vieneu-rust", "model": "v3nano", "model_loaded": true,
            "state": "ready", "enabled": true, "queue_length": 0
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
fn default_voice() -> String { "my_duyen".to_string() }

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

    let raw_trimmed = q.text.trim();
    if raw_trimmed.is_empty() {
        return Response::builder()
            .status(StatusCode::BAD_REQUEST)
            .body(Body::from(r#"{"detail":"empty_text"}"#))
            .unwrap();
    }

    let preset = preset_name_for_voice(&q.voice);
    let norm = normalize_tts_text(raw_trimmed);
    let cache_dir = state.data_dir.join("tts_cache");

    for candidate in [&norm, raw_trimmed] {
        for mode in ["v3nano", "v3turbo"] {
            let md5_hex = compute_tts_md5(mode, candidate, preset);
            let mp3_path = cache_dir.join(format!("{}.mp3", md5_hex));
            if let Ok(bytes) = fs::read(&mp3_path) {
                if !bytes.is_empty() {
                    let dur_ms = read_or_estimate_duration(&cache_dir, &md5_hex, bytes.len());
                    return build_mp3_response(bytes, dur_ms, "RUST-VIENEU-CACHE");
                }
            }
        }
    }

    let lang = q.lang.as_deref().unwrap_or("vi");
    let tl = if lang.starts_with("en") || q.voice == "adam" { "en" } else { "vi" };
    if let Some(mp3_bytes) = synthesize_mp3_fast(&state.http_client, &norm, tl).await {
        let dur_ms = ((mp3_bytes.len() as u64) / 8).max(300);
        let md5_hex = compute_tts_md5("v3nano", &norm, preset);
        let _ = fs::create_dir_all(&cache_dir);
        let _ = fs::write(cache_dir.join(format!("{}.ms", md5_hex)), dur_ms.to_string());
        let _ = fs::write(cache_dir.join(format!("{}.mp3", md5_hex)), &mp3_bytes);
        return build_mp3_response(mp3_bytes, dur_ms, "RUST-INSTANT-TTS");
    }

    Response::builder()
        .status(StatusCode::SERVICE_UNAVAILABLE)
        .header(header::CONTENT_TYPE, "application/json")
        .header("X-Local-Tts-Reason", "unavailable")
        .header("Retry-After", "10")
        .body(Body::from(r#"{"detail":"local_tts_unavailable"}"#))
        .unwrap()
}

pub async fn prefetch_tts(
    State(state): State<AppState>,
    Json(payload): Json<Value>,
) -> impl IntoResponse {
    let voice = payload.get("voice").and_then(|v| v.as_str()).unwrap_or("my_duyen").to_string();
    let lang = payload.get("lang").and_then(|v| v.as_str()).unwrap_or("vi").to_string();
    let texts: Vec<String> = payload
        .get("texts")
        .and_then(|v| v.as_array())
        .map(|arr| {
            arr.iter()
                .filter_map(|x| x.as_str().map(|s| s.trim().to_string()))
                .filter(|s| !s.is_empty())
                .take(6)
                .collect()
        })
        .unwrap_or_default();
    let count = texts.len();

    tokio::spawn(async move {
        let preset = preset_name_for_voice(&voice);
        let cache_dir = state.data_dir.join("tts_cache");
        let tl = if lang.starts_with("en") || voice == "adam" { "en" } else { "vi" };
        for t in texts {
            let norm = normalize_tts_text(&t);
            let md5_hex = compute_tts_md5("v3nano", &norm, preset);
            let mp3_path = cache_dir.join(format!("{}.mp3", md5_hex));
            if mp3_path.exists() { continue; }
            if let Some(bytes) = synthesize_mp3_fast(&state.http_client, &norm, tl).await {
                let dur_ms = ((bytes.len() as u64) / 8).max(300);
                let _ = fs::write(cache_dir.join(format!("{}.ms", md5_hex)), dur_ms.to_string());
                let _ = fs::write(mp3_path, bytes);
            }
        }
    });

    (StatusCode::OK, Json(json!({ "status": "ok", "queued": count, "device": 0, "requested": count })))
}

pub async fn prerender_tts(Json(payload): Json<Value>) -> impl IntoResponse {
    let chapter_number = payload.get("chapter_number").and_then(|v| v.as_i64()).unwrap_or(1);
    let count = payload.get("count").and_then(|v| v.as_i64()).unwrap_or(1).clamp(1, 5);
    let from_seg = payload.get("from_segment").and_then(|v| v.as_i64()).unwrap_or(0);
    let chapters: Vec<Value> = (0..count)
        .map(|offset| {
            json!({
                "chapter_number": chapter_number + offset,
                "state": "ready", "total_segments": 1, "ready_segments": 1,
                "estimated_seconds_remaining": 0, "lead_needed_segments": 1,
                "segment_count": 0, "device_segments": 0,
                "start_segment": if offset == 0 { from_seg } else { 0 },
                "ready_prefix_segments": 1, "can_start": true
            })
        })
        .collect();
    (
        StatusCode::OK,
        Json(json!({
            "status": "ok",
            "engine": { "state": "ready", "model": "v3nano", "queue_length": 0 },
            "chapters": chapters
        })),
    )
}

fn compute_tts_md5(mode: &str, norm_text: &str, preset: &str) -> String {
    let mut hasher = Md5::new();
    hasher.update(format!("v5_vieneu|{}|{}|{}", mode, norm_text, preset).as_bytes());
    hex::encode(hasher.finalize())
}

fn read_or_estimate_duration(cache_dir: &std::path::Path, md5_hex: &str, byte_len: usize) -> u64 {
    fs::read_to_string(cache_dir.join(format!("{}.ms", md5_hex)))
        .ok()
        .and_then(|s| s.trim().parse::<u64>().ok())
        .unwrap_or_else(|| ((byte_len as u64) / 8).max(300))
}

fn build_mp3_response(bytes: Vec<u8>, dur_ms: u64, engine_tag: &'static str) -> Response<Body> {
    Response::builder()
        .status(StatusCode::OK)
        .header(header::CONTENT_TYPE, "audio/mpeg")
        .header(header::ACCEPT_RANGES, "bytes")
        .header("X-Audio-Duration-Ms", dur_ms.to_string())
        .header("X-TTS-Engine", engine_tag)
        .body(Body::from(bytes))
        .unwrap()
}

fn normalize_tts_text(text: &str) -> String {
    let mut s = text
        .replace(['「', '『'], "“")
        .replace(['」', '』'], "”")
        .replace(['[', '【', '〔'], "(")
        .replace([']', '】', '〕'], ")")
        .replace(['*', '#', '~', '^', '_', '|', '\\', '@', '`', '◆', '■', '★'], " ");
    while s.contains("  ") {
        s = s.replace("  ", " ");
    }
    s.trim().to_string()
}

async fn synthesize_mp3_fast(client: &reqwest::Client, text: &str, tl: &str) -> Option<Vec<u8>> {
    let chunks = split_tts_chunks(text, 175);
    if chunks.is_empty() { return None; }
    let mut combined = Vec::new();
    for chunk in chunks {
        let resp = client
            .get("https://translate.google.com/translate_tts")
            .query(&[("ie", "UTF-8"), ("client", "tw-ob"), ("tl", tl), ("q", chunk.as_str())])
            .send()
            .await
            .ok()?;
        if !resp.status().is_success() { return None; }
        let bytes = resp.bytes().await.ok()?;
        if bytes.is_empty() { return None; }
        combined.extend_from_slice(&bytes);
    }
    if combined.is_empty() { None } else { Some(combined) }
}

fn split_tts_chunks(text: &str, max_chars: usize) -> Vec<String> {
    let mut out = Vec::new();
    let mut cur = String::new();
    for word in text.split_whitespace() {
        if cur.chars().count() + word.chars().count() + 1 > max_chars && !cur.is_empty() {
            out.push(cur.trim().to_string());
            cur.clear();
        }
        if !cur.is_empty() { cur.push(' '); }
        cur.push_str(word);
        if cur.chars().count() >= 90 && (word.ends_with('.') || word.ends_with('!') || word.ends_with('?') || word.ends_with(';')) {
            out.push(cur.trim().to_string());
            cur.clear();
        }
    }
    if !cur.trim().is_empty() { out.push(cur.trim().to_string()); }
    out
}
