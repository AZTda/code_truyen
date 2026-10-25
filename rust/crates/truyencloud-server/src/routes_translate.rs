use crate::routes_extra::read_active_model;
use crate::scraper::fetch_chapter_body;
use crate::state::AppState;
use axum::extract::{Path, Query, State};
use axum::http::StatusCode;
use axum::response::IntoResponse;
use axum::Json;
use serde::Deserialize;
use serde_json::{json, Value};
use tokio::sync::Semaphore;
use truyencloud_core::hanviet::translate_hanviet_phrases;
use truyencloud_core::text_paging::clean_paragraphs;

static AGY_SEMAPHORE: Semaphore = Semaphore::const_new(2);

#[derive(Debug, Deserialize)]
pub struct RecrawlQuery {
    #[serde(default = "default_true")]
    pub auto_translate: bool,
    #[serde(default, rename = "custom_prompt")]
    pub _custom_prompt: Option<String>,
}
fn default_true() -> bool { true }

pub async fn translate_chapter(
    State(state): State<AppState>,
    Json(payload): Json<Value>,
) -> impl IntoResponse {
    let novel_id = payload.get("novel_id").and_then(|v| v.as_str()).unwrap_or("").to_string();
    let chapter_number = payload.get("chapter_number").and_then(|v| v.as_i64()).unwrap_or(1) as i32;
    let force = payload.get("force").and_then(|v| v.as_bool()).unwrap_or(false);
    let target_language = payload.get("target_language").and_then(|v| v.as_str()).unwrap_or("vi").trim().to_lowercase();
    let english_level = payload.get("english_level").and_then(|v| v.as_str()).unwrap_or("b1").trim().to_lowercase();

    let cache_field = match target_language.as_str() {
        "en" => format!("translated_content_en_{}", english_level),
        "bilingual" => format!("translated_content_bilingual_{}", english_level),
        _ => "translated_content".to_string(),
    };

    let mut ch_val = state
        .get_chapter_value(&novel_id, chapter_number)
        .unwrap_or_else(|| json!({ "novel_id": novel_id, "chapter_number": chapter_number }));

    if !force {
        if let Some(existing) = ch_val.get(&cache_field).and_then(|v| v.as_str()).filter(|s| !s.trim().is_empty()) {
            let title_vi = ch_val.get("title_vi").and_then(|v| v.as_str()).unwrap_or("").to_string();
            return (
                StatusCode::OK,
                Json(json!({
                    "novel_id": novel_id, "chapter_number": chapter_number,
                    "translated_content": existing, "title_vi": title_vi,
                    "cached": true, "engine": "RUST_CACHE", "translation_engine": "RUST_CACHE",
                    "is_fallback": false
                })),
            );
        }
    }

    let mut raw_content = payload
        .get("content")
        .and_then(|v| v.as_str())
        .filter(|s| !s.trim().is_empty())
        .map(ToOwned::to_owned)
        .or_else(|| ch_val.get("original_content").and_then(|v| v.as_str()).filter(|s| !s.trim().is_empty()).map(ToOwned::to_owned))
        .unwrap_or_default();

    if raw_content.trim().is_empty() {
        let ch_url = ch_val.get("url").or_else(|| ch_val.get("source_url")).and_then(|v| v.as_str()).unwrap_or("").to_string();
        if ch_url.starts_with("http") {
            if let Some(body) = fetch_chapter_body(&state.http_client, &ch_url).await {
                raw_content = body.clone();
                if let Some(obj) = ch_val.as_object_mut() {
                    obj.insert("original_content".into(), Value::String(body));
                }
            }
        }
    }

    let (translated, engine_name) = translate_pipeline_lang(&state, &raw_content, &target_language, &english_level).await;
    if !novel_id.is_empty() && !translated.is_empty() {
        if let Some(obj) = ch_val.as_object_mut() {
            obj.insert(cache_field, Value::String(translated.clone()));
            obj.insert("translation_engine".into(), Value::String(engine_name.clone()));
        }
        state.save_chapter_value(&novel_id, chapter_number, &ch_val);
    }

    let title_vi = ch_val
        .get("title_vi")
        .and_then(|v| v.as_str())
        .filter(|s| !s.trim().is_empty())
        .map(ToOwned::to_owned)
        .unwrap_or_else(|| format!("Chương {}", chapter_number));

    (
        StatusCode::OK,
        Json(json!({
            "novel_id": novel_id, "chapter_number": chapter_number,
            "translated_content": translated, "title_vi": title_vi,
            "cached": false, "engine": engine_name, "translation_engine": engine_name,
            "is_fallback": false
        })),
    )
}

pub async fn recrawl_chapter(
    State(state): State<AppState>,
    Path((novel_id, chapter_number)): Path<(String, i32)>,
    Query(q): Query<RecrawlQuery>,
) -> impl IntoResponse {
    let mut val = state.get_chapter_value(&novel_id, chapter_number).unwrap_or_else(|| {
        json!({
            "novel_id": novel_id, "chapter_number": chapter_number,
            "title": format!("Chương {}", chapter_number), "title_vi": format!("Chương {}", chapter_number),
            "original_content": "", "translated_content": null
        })
    });

    let ch_url = val.get("url").or_else(|| val.get("source_url")).and_then(|v| v.as_str()).unwrap_or("").to_string();
    let mut orig = val.get("original_content").and_then(|v| v.as_str()).unwrap_or("").to_string();

    if ch_url.starts_with("http") {
        if let Some(body) = fetch_chapter_body(&state.http_client, &ch_url).await {
            orig = body.clone();
            if let Some(obj) = val.as_object_mut() {
                obj.insert("original_content".into(), Value::String(body));
            }
        }
    }

    let mut trans = val.get("translated_content").and_then(|v| v.as_str()).filter(|s| !s.trim().is_empty()).map(ToOwned::to_owned);
    let mut engine = val.get("translation_engine").and_then(|v| v.as_str()).unwrap_or("RUST_AXUM").to_string();

    if q.auto_translate && !orig.trim().is_empty() {
        let (t, e) = translate_pipeline_lang(&state, &orig, "vi", "b1").await;
        if !t.is_empty() {
            trans = Some(t.clone());
            engine = e.clone();
            if let Some(obj) = val.as_object_mut() {
                obj.insert("translated_content".into(), Value::String(t));
                obj.insert("translation_engine".into(), Value::String(e));
            }
        }
    }
    state.save_chapter_value(&novel_id, chapter_number, &val);

    (
        StatusCode::OK,
        Json(json!({
            "novel_id": novel_id, "chapter_number": chapter_number,
            "title": val.get("title").and_then(|v| v.as_str()).unwrap_or(&format!("Chương {}", chapter_number)),
            "title_vi": val.get("title_vi").and_then(|v| v.as_str()).unwrap_or(""),
            "original_content": orig, "translated_content": trans,
            "translation_engine": engine, "is_fallback": false, "cached": false
        })),
    )
}

pub async fn translate_pipeline_lang(
    state: &AppState,
    raw_text: &str,
    target_lang: &str,
    level: &str,
) -> (String, String) {
    let cleaned = clean_paragraphs(raw_text).join("\n\n");
    if cleaned.is_empty() {
        return ("(Chương này chưa có nội dung trên nguồn truyện)".to_string(), "RUST_EMPTY".to_string());
    }

    if target_lang == "en" {
        if let Some(en) = translate_via_google_gtx(&state.http_client, &cleaned, "zh-CN", "en").await {
            return (en, format!("RUST_EN_{}", level.to_uppercase()));
        }
    } else if target_lang == "bilingual" {
        let en = translate_via_google_gtx(&state.http_client, &cleaned, "zh-CN", "en").await.unwrap_or_default();
        let vi = translate_via_google_gtx(&state.http_client, &cleaned, "zh-CN", "vi").await.unwrap_or_default();
        if !en.is_empty() && !vi.is_empty() {
            let en_paras: Vec<&str> = en.split("\n\n").collect();
            let vi_paras: Vec<&str> = vi.split("\n\n").collect();
            let mut merged = Vec::new();
            for i in 0..en_paras.len().max(vi_paras.len()) {
                if let Some(e) = en_paras.get(i) { merged.push((*e).to_string()); }
                if let Some(v) = vi_paras.get(i) { merged.push(format!("▸ {}", v)); }
            }
            return (merged.join("\n\n"), "RUST_BILINGUAL".to_string());
        }
    }

    let snippet: String = cleaned.chars().take(5200).collect();

    let custom_rule = std::fs::read_to_string(state.data_dir.join("translation_profile.json"))
        .ok()
        .and_then(|s| serde_json::from_str::<Value>(&s).ok())
        .and_then(|v| v.get("custom_prompt").and_then(|x| x.as_str()).map(ToOwned::to_owned))
        .unwrap_or_else(|| "Văn phong tiểu thuyết mượt mà, giữ nguyên tên riêng Hán-Việt.".to_string());

    let (_, _, cfg_timeout) = read_active_model(state);
    let prompt = format!(
        "Dịch chương tiểu thuyết tiếng Trung sau sang tiếng Việt tự nhiên ({}). Chỉ trả về đúng nội dung bản dịch tiếng Việt, không lời dẫn:\n\n{}",
        custom_rule, snippet
    );
    if let Some(out) = run_agy_gemini(state, &prompt, cfg_timeout.clamp(20, 90) as u64).await {
        if out.chars().count() > 80 {
            return (out, "ANTIGRAVITY_CLI".to_string());
        }
    }

    if let Some(gtx_out) = translate_via_google_gtx(&state.http_client, &cleaned, "zh-CN", "vi").await {
        if gtx_out.chars().count() > 60 {
            return (gtx_out, "RUST_NEURAL_MT".to_string());
        }
    }

    let req_body = json!({
        "messages": [
            {"role": "system", "content": "Bạn là dịch giả tiểu thuyết tiếng Trung sang tiếng Việt chuyên nghiệp. Chỉ trả về bản dịch tiếng Việt."},
            {"role": "user", "content": snippet}
        ],
        "temperature": 0.3, "max_tokens": 3000
    });
    if let Ok(resp) = state.http_client.post(&state.qwen_url).json(&req_body).send().await {
        if let Ok(val) = resp.json::<Value>().await {
            if let Some(text) = val.pointer("/choices/0/message/content").and_then(|v| v.as_str()) {
                let out = text.split("</think>").last().unwrap_or(text).trim().to_string();
                if !out.is_empty() {
                    return (out, "RUST_QWEN_CPP".to_string());
                }
            }
        }
    }

    (translate_hanviet_phrases(&cleaned), "RUST_HANVIET".to_string())
}

pub async fn run_agy_gemini(state: &AppState, prompt: &str, timeout_secs: u64) -> Option<String> {
    if !std::path::Path::new("/usr/local/bin/agy").exists() {
        return None;
    }
    let _permit = AGY_SEMAPHORE.acquire().await.ok()?;
    let (active_model, active_effort, _) = read_active_model(state);
    let mut cmd = tokio::process::Command::new("/usr/local/bin/agy");
    cmd.kill_on_drop(true).args([
        "-p",
        prompt,
        "--output-format",
        "text",
        "--effort",
        &active_effort,
        "--model",
        &active_model,
        "--disable-slash-commands",
        "--dangerously-skip-permissions",
    ]);
    let output = tokio::time::timeout(std::time::Duration::from_secs(timeout_secs), cmd.output())
        .await
        .ok()?
        .ok()?;
    if !output.status.success() {
        return None;
    }
    let out = String::from_utf8_lossy(&output.stdout).trim().to_string();
    (!out.is_empty()).then_some(out)
}

pub async fn translate_via_google_gtx(client: &reqwest::Client, text: &str, sl: &str, tl: &str) -> Option<String> {
    let mut chunks = Vec::new();
    let mut cur = String::new();
    for para in text.split("\n\n") {
        if cur.chars().count() + para.chars().count() > 1800 && !cur.is_empty() {
            chunks.push(cur.clone());
            cur.clear();
        }
        if !cur.is_empty() { cur.push_str("\n\n"); }
        cur.push_str(para);
    }
    if !cur.is_empty() { chunks.push(cur); }

    let mut out_parts = Vec::new();
    for chunk in chunks {
        let resp = client
            .get("https://translate.googleapis.com/translate_a/single")
            .query(&[("client", "gtx"), ("sl", sl), ("tl", tl), ("dt", "t"), ("q", chunk.as_str())])
            .send()
            .await
            .ok()?;
        let val: Value = resp.json().await.ok()?;
        let sentences = val.get(0)?.as_array()?;
        let mut part = String::new();
        for s in sentences {
            if let Some(seg) = s.get(0).and_then(|v| v.as_str()) {
                part.push_str(seg);
            }
        }
        if !part.trim().is_empty() {
            out_parts.push(part.trim().to_string());
        }
    }
    if out_parts.is_empty() { None } else { Some(out_parts.join("\n\n")) }
}
