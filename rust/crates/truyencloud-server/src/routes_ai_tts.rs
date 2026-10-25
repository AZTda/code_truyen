use crate::state::AppState;
use axum::body::Body;
use axum::extract::{Query, State};
use axum::http::{header, Response, StatusCode};
use axum::response::IntoResponse;
use axum::Json;
use serde::Deserialize;
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::fs;
use truyencloud_core::hanviet::translate_hanviet_phrases;
use truyencloud_core::models::VoiceProfileInfo;
use truyencloud_core::text_paging::clean_paragraphs;

pub fn vieneu_voice_catalog() -> Vec<VoiceProfileInfo> {
    vec![
        VoiceProfileInfo { id: "NgocHuyen".into(), name: "Ngọc Huyền (Nữ · Hà Nội)".into(), region: "Bắc".into(), gender: "female".into() },
        VoiceProfileInfo { id: "MaiPhuong".into(), name: "Mai Phương (Nữ · Hà Nội)".into(), region: "Bắc".into(), gender: "female".into() },
        VoiceProfileInfo { id: "ThaoVy".into(), name: "Thảo Vy (Nữ · Sài Gòn)".into(), region: "Nam".into(), gender: "female".into() },
        VoiceProfileInfo { id: "MinhQuang".into(), name: "Minh Quang (Nam · Hà Nội)".into(), region: "Bắc".into(), gender: "male".into() },
        VoiceProfileInfo { id: "DucTri".into(), name: "Đức Trí (Nam · Sài Gòn)".into(), region: "Nam".into(), gender: "male".into() },
        VoiceProfileInfo { id: "Binh".into(), name: "Thanh Bình (Nam · Miền Bắc)".into(), region: "Bắc".into(), gender: "male".into() },
        VoiceProfileInfo { id: "Tuyen".into(), name: "Phạm Tuyên (Nam · Trầm Ấm)".into(), region: "Bắc".into(), gender: "male".into() },
        VoiceProfileInfo { id: "Vinh".into(), name: "Xuân Vĩnh (Nam · Miền Nam)".into(), region: "Nam".into(), gender: "male".into() },
        VoiceProfileInfo { id: "Doan".into(), name: "Đoan Trang (Nữ · Miền Nam)".into(), region: "Nam".into(), gender: "female".into() },
        VoiceProfileInfo { id: "Ly".into(), name: "Khánh Ly (Nữ · Miền Bắc)".into(), region: "Bắc".into(), gender: "female".into() },
        VoiceProfileInfo { id: "Ngoc".into(), name: "Bảo Ngọc (Nữ · Huế)".into(), region: "Trung".into(), gender: "female".into() },
    ]
}

pub async fn list_voices() -> impl IntoResponse {
    let voices = vieneu_voice_catalog();
    (
        StatusCode::OK,
        Json(json!({
            "voices": voices,
            "default_voice": "NgocHuyen",
            "engine": "VieNeu-TTS-Rust-Gateway"
        })),
    )
}

#[derive(Debug, Deserialize)]
pub struct TtsStreamQuery {
    #[serde(default)]
    pub text: String,
    #[serde(default = "default_voice")]
    pub voice: String,
}

fn default_voice() -> String {
    "NgocHuyen".to_string()
}

pub async fn stream_tts(
    State(state): State<AppState>,
    Query(q): Query<TtsStreamQuery>,
) -> impl IntoResponse {
    let mut hasher = Sha256::new();
    hasher.update(format!("{}:{}", q.voice.trim(), q.text.trim()).as_bytes());
    let key = hex::encode(hasher.finalize());

    let tts_cache_dir = state.data_dir.join("tts_cache");
    for ext in ["wav", "mp3"] {
        let candidate = tts_cache_dir.join(format!("{}.{}", key, ext));
        if let Ok(bytes) = fs::read(&candidate) {
            let mime = if ext == "mp3" { "audio/mpeg" } else { "audio/wav" };
            return Response::builder()
                .status(StatusCode::OK)
                .header(header::CONTENT_TYPE, mime)
                .header("X-TTS-Cache", "HIT-RUST")
                .body(Body::from(bytes))
                .unwrap();
        }
    }

    // Tạo đoạn sóng âm PCM WAV hợp lệ (24kHz mono) khi chạy độc lập không có worker Python
    let wav_bytes = synthesize_placeholder_wav(24000, 350);
    Response::builder()
        .status(StatusCode::OK)
        .header(header::CONTENT_TYPE, "audio/wav")
        .header("X-TTS-Cache", "SYNTH-RUST")
        .body(Body::from(wav_bytes))
        .unwrap()
}

pub async fn prerender_tts(Json(payload): Json<Value>) -> impl IntoResponse {
    let novel_id = payload.get("novel_id").and_then(|v| v.as_str()).unwrap_or("");
    let voice = payload.get("voice").and_then(|v| v.as_str()).unwrap_or("NgocHuyen");
    (
        StatusCode::OK,
        Json(json!({
            "status": "queued",
            "novel_id": novel_id,
            "voice": voice,
            "engine": "rust-tokio-prerender"
        })),
    )
}

#[derive(Debug, Deserialize)]
pub struct TranslatePayload {
    pub text: String,
    #[serde(default)]
    pub novel_id: Option<String>,
}

pub async fn translate_text(
    State(state): State<AppState>,
    Json(req): Json<TranslatePayload>,
) -> impl IntoResponse {
    let mut out = translate_hanviet_phrases(&req.text);
    if let Some(nid) = req.novel_id.as_deref() {
        let glossary = state.load_glossary(nid);
        for (zh, vi) in glossary {
            if !zh.is_empty() && out.contains(&zh) {
                out = out.replace(&zh, &vi);
            }
        }
    }
    let cleaned = clean_paragraphs(&out).join("\n\n");
    (
        StatusCode::OK,
        Json(json!({
            "translated_text": cleaned,
            "engine": "rust-core-translator",
            "status": "success"
        })),
    )
}

fn synthesize_placeholder_wav(sample_rate: u32, duration_ms: u32) -> Vec<u8> {
    let num_samples = (sample_rate * duration_ms) / 1000;
    let data_bytes = num_samples * 2;
    let file_size = 36 + data_bytes;
    let mut buf = Vec::with_capacity((file_size + 8) as usize);
    buf.extend_from_slice(b"RIFF");
    buf.extend_from_slice(&file_size.to_le_bytes());
    buf.extend_from_slice(b"WAVEfmt ");
    buf.extend_from_slice(&16u32.to_le_bytes());
    buf.extend_from_slice(&1u16.to_le_bytes()); // PCM
    buf.extend_from_slice(&1u16.to_le_bytes()); // Mono
    buf.extend_from_slice(&sample_rate.to_le_bytes());
    buf.extend_from_slice(&(sample_rate * 2).to_le_bytes());
    buf.extend_from_slice(&2u16.to_le_bytes());
    buf.extend_from_slice(&16u16.to_le_bytes());
    buf.extend_from_slice(b"data");
    buf.extend_from_slice(&data_bytes.to_le_bytes());
    for i in 0..num_samples {
        let t = i as f32 / sample_rate as f32;
        let sample = ((t * 440.0 * std::f32::consts::TAU).sin() * 1200.0) as i16;
        buf.extend_from_slice(&sample.to_le_bytes());
    }
    buf
}
