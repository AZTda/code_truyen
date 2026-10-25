use crate::scraper::fetch_chapter_body;
use crate::state::AppState;
use axum::extract::State;
use axum::http::StatusCode;
use axum::response::IntoResponse;
use axum::Json;
use serde_json::{json, Value};
use truyencloud_core::hanviet::translate_hanviet_phrases;
use truyencloud_core::text_paging::clean_paragraphs;

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

    let mut ch_val = state
        .get_chapter_value(&novel_id, chapter_number)
        .unwrap_or_else(|| json!({ "novel_id": novel_id, "chapter_number": chapter_number }));

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

    let mut raw_content = payload
        .get("content")
        .and_then(|v| v.as_str())
        .filter(|s| !s.trim().is_empty())
        .map(ToOwned::to_owned)
        .or_else(|| {
            ch_val
                .get("original_content")
                .and_then(|v| v.as_str())
                .filter(|s| !s.trim().is_empty())
                .map(ToOwned::to_owned)
        })
        .unwrap_or_default();

    // Tự động cào nội dung gốc qua proxy `69shuba-com.translate.goog` nếu chương chưa có chữ
    if raw_content.trim().is_empty() {
        let ch_url = ch_val
            .get("url")
            .or_else(|| ch_val.get("source_url"))
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string();
        if ch_url.starts_with("http") {
            if let Some(body) = fetch_chapter_body(&state.http_client, &ch_url).await {
                raw_content = body.clone();
                if let Some(obj) = ch_val.as_object_mut() {
                    obj.insert("original_content".into(), Value::String(body));
                }
            }
        }
    }

    let (translated, engine_name) = translate_pipeline(&state, &raw_content).await;
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
        .filter(|s| !s.trim().is_empty())
        .map(ToOwned::to_owned)
        .unwrap_or_else(|| format!("Chương {}", chapter_number));

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

async fn translate_pipeline(state: &AppState, raw_text: &str) -> (String, String) {
    let cleaned = clean_paragraphs(raw_text).join("\n\n");
    if cleaned.is_empty() {
        return (
            "(Chương này chưa có nội dung trên nguồn truyện)".to_string(),
            "RUST_EMPTY".to_string(),
        );
    }
    let snippet: String = cleaned.chars().take(5200).collect();

    // Tầng 1: Gọi Antigravity CLI (`/usr/local/bin/agy`, model `gemini-3.8-flash-low`)
    if std::path::Path::new("/usr/local/bin/agy").exists() {
        let prompt = format!(
            "Dịch chương tiểu thuyết tiếng Trung sau sang tiếng Việt tự nhiên, văn phong tiểu thuyết mượt mà, giữ nguyên tên riêng Hán-Việt. Chỉ trả về đúng nội dung bản dịch tiếng Việt, không lời dẫn:\n\n{}",
            snippet
        );
        let fut = tokio::process::Command::new("/usr/local/bin/agy")
            .args([
                "-p",
                &prompt,
                "--output-format",
                "text",
                "--effort",
                "low",
                "--model",
                "gemini-3.8-flash-low",
                "--disable-slash-commands",
                "--dangerously-skip-permissions",
            ])
            .output();
        if let Ok(Ok(output)) = tokio::time::timeout(std::time::Duration::from_secs(35), fut).await
        {
            if output.status.success() {
                let out = String::from_utf8_lossy(&output.stdout).trim().to_string();
                if out.chars().count() > 80 {
                    return (out, "ANTIGRAVITY_CLI".to_string());
                }
            }
        }
    }

    // Tầng 2: Dịch tức thì qua Google Neural MT (`translate.googleapis.com`) để không bao giờ kẹt
    if let Some(gtx_out) = translate_via_google_gtx(&state.http_client, &cleaned).await {
        if gtx_out.chars().count() > 60 {
            return (gtx_out, "RUST_NEURAL_MT".to_string());
        }
    }

    // Tầng 3: Qwen3 C++ (`llama.cpp` server trên minipc)
    let req_body = json!({
        "messages": [
            {"role": "system", "content": "Bạn là dịch giả tiểu thuyết tiếng Trung sang tiếng Việt chuyên nghiệp. Chỉ trả về bản dịch tiếng Việt."},
            {"role": "user", "content": snippet}
        ],
        "temperature": 0.3,
        "max_tokens": 3000
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

async fn translate_via_google_gtx(client: &reqwest::Client, text: &str) -> Option<String> {
    let mut chunks = Vec::new();
    let mut cur = String::new();
    for para in text.split("\n\n") {
        if cur.chars().count() + para.chars().count() > 1800 && !cur.is_empty() {
            chunks.push(cur.clone());
            cur.clear();
        }
        if !cur.is_empty() {
            cur.push_str("\n\n");
        }
        cur.push_str(para);
    }
    if !cur.is_empty() {
        chunks.push(cur);
    }

    let mut out_parts = Vec::new();
    for chunk in chunks {
        let resp = client
            .get("https://translate.googleapis.com/translate_a/single")
            .query(&[
                ("client", "gtx"),
                ("sl", "zh-CN"),
                ("tl", "vi"),
                ("dt", "t"),
                ("q", chunk.as_str()),
            ])
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
    if out_parts.is_empty() {
        None
    } else {
        Some(out_parts.join("\n\n"))
    }
}
