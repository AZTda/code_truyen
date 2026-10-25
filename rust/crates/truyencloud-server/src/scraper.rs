use crate::state::AppState;
use axum::extract::State;
use axum::http::StatusCode;
use axum::response::IntoResponse;
use axum::Json;
use encoding_rs::{GB18030, UTF_8};
use scraper::{Html, Selector};
use serde::Deserialize;
use serde_json::json;
use truyencloud_core::text_paging::clean_paragraphs;

#[derive(Debug, Deserialize)]
pub struct ScrapeRequest {
    pub url: String,
}

/// Giải mã tự động byte trang web tiếng Trung (`UTF-8` hoặc `GBK / GB18030` của `69shuba`, `piaotia`).
pub fn decode_html_bytes(bytes: &[u8], content_type: Option<&str>) -> String {
    let ct_lower = content_type.unwrap_or("").to_ascii_lowercase();
    if ct_lower.contains("gbk") || ct_lower.contains("gb2312") || ct_lower.contains("gb18030") {
        let (cow, _, _) = GB18030.decode(bytes);
        return cow.into_owned();
    }
    let (utf8_cow, _, had_errors) = UTF_8.decode(bytes);
    if !had_errors {
        let text = utf8_cow.into_owned();
        if !text.to_ascii_lowercase().contains("charset=gb") {
            return text;
        }
    }
    let (gbk_cow, _, _) = GB18030.decode(bytes);
    gbk_cow.into_owned()
}

/// Bóc tách tiêu đề và nội dung chương từ HTML của `69shuba`, `piaotia`, `uukanshu` hoặc trang bất kỳ.
pub fn parse_chapter_html(html: &str) -> (String, String) {
    let doc = Html::parse_document(html);

    let title_selectors = ["h1.hide720", ".txtnav h1", "#content h1", "h1", "title"];
    let mut title = String::new();
    for sel_str in title_selectors {
        if let Ok(sel) = Selector::parse(sel_str) {
            if let Some(el) = doc.select(&sel).next() {
                let t = el.text().collect::<Vec<_>>().join(" ").trim().to_string();
                if !t.is_empty() {
                    title = t;
                    break;
                }
            }
        }
    }

    let body_selectors = [
        "div.txtnav",
        "div#content",
        "div#htmlContent",
        "div.read-content",
        "article",
        "body",
    ];
    let mut raw_body = String::new();
    for sel_str in body_selectors {
        if let Ok(sel) = Selector::parse(sel_str) {
            if let Some(el) = doc.select(&sel).next() {
                let text = el.text().collect::<Vec<_>>().join("\n");
                if text.chars().count() > 80 {
                    raw_body = text;
                    break;
                }
            }
        }
    }

    let cleaned = clean_paragraphs(&raw_body).join("\n\n");
    (title, cleaned)
}

pub async fn scrape_url(
    State(state): State<AppState>,
    Json(req): Json<ScrapeRequest>,
) -> impl IntoResponse {
    let url = req.url.trim();
    if !url.starts_with("http://") && !url.starts_with("https://") {
        return (
            StatusCode::BAD_REQUEST,
            Json(json!({"detail": "URL phải bắt đầu bằng http:// hoặc https://"})),
        );
    }

    let resp = match state.http_client.get(url).send().await {
        Ok(r) => r,
        Err(e) => {
            return (
                StatusCode::BAD_GATEWAY,
                Json(json!({"detail": format!("Không tải được trang nguồn: {}", e)})),
            );
        }
    };

    let ct = resp
        .headers()
        .get(reqwest::header::CONTENT_TYPE)
        .and_then(|v| v.to_str().ok())
        .map(ToOwned::to_owned);
    let bytes = match resp.bytes().await {
        Ok(b) => b,
        Err(e) => {
            return (
                StatusCode::BAD_GATEWAY,
                Json(json!({"detail": format!("Lỗi đọc luồng byte: {}", e)})),
            );
        }
    };

    let html = decode_html_bytes(&bytes, ct.as_deref());
    let (title, content) = parse_chapter_html(&html);

    (
        StatusCode::OK,
        Json(json!({
            "url": url,
            "title": title,
            "content": content,
            "char_count": content.chars().count(),
            "engine": "rust-scraper-gb18030"
        })),
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_69shuba_style_chapter_html_cleanly() {
        let sample = r#"
        <html><body>
          <div class="txtnav">
            <h1 class="hide720">第1章 离别</h1>
            <p>　　铁柱坐在村口的老树下，望着远处的山峦。</p>
            <p>请记住本书首发域名：www.69shuba.com</p>
            <p>　　父亲拍了拍他的肩膀，叹了口气，转身走回屋里准备行囊。</p>
          </div>
        </body></html>
        "#;
        let (title, body) = parse_chapter_html(sample);
        assert_eq!(title, "第1章 离别");
        assert!(body.contains("铁柱坐在村口"));
        assert!(!body.contains("www.69shuba.com"));
    }
}
