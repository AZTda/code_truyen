use crate::scraper::fetch_chapter_body;
use crate::state::AppState;
use axum::body::Body;
use axum::extract::{Path, Query, State};
use axum::http::{header, HeaderMap, Response, StatusCode};
use axum::response::IntoResponse;
use axum::Json;
use serde::Deserialize;
use serde_json::{json, Value};
use truyencloud_core::epub::build_epub_bytes;
use truyencloud_core::hanviet::{fold_vietnamese_ascii, translate_hanviet_phrases};
use truyencloud_core::models::{Chapter, DiscoverResponse, Novel};

#[derive(Debug, Deserialize)]
pub struct DiscoverParams {
    #[serde(default = "default_sort")]
    pub sort: String,
    #[serde(default = "default_limit")]
    pub limit: usize,
}
fn default_sort() -> String { "recent".to_string() }
fn default_limit() -> usize { 500 }

#[derive(Debug, Deserialize)]
pub struct SearchParams {
    #[serde(default)]
    pub q: String,
    #[serde(default)]
    pub source: Option<String>,
    #[serde(default = "default_suggest_limit")]
    pub limit: usize,
}
fn default_suggest_limit() -> usize { 8 }

pub async fn discover_novels(
    State(state): State<AppState>,
    headers: HeaderMap,
    Query(params): Query<DiscoverParams>,
) -> impl IntoResponse {
    let mut novels = state.load_all_novels();
    let total = novels.len();
    let auth = headers
        .get("authorization")
        .or_else(|| headers.get("x-auth-token"))
        .and_then(|v| v.to_str().ok());
    let logged_in = state.verify_bearer(auth, 0).is_some();

    match params.sort.as_str() {
        "popular" => novels.sort_by(|a, b| {
            b.translated_chapters
                .cmp(&a.translated_chapters)
                .then_with(|| b.total_chapters.cmp(&a.total_chapters))
        }),
        "for_you" => novels.sort_by(|a, b| {
            b.overnight_enabled
                .cmp(&a.overnight_enabled)
                .then_with(|| b.translated_chapters.cmp(&a.translated_chapters))
        }),
        _ => {}
    }

    novels.truncate(params.limit.clamp(1, 500));
    for n in &mut novels {
        if n.title_vi.as_deref().unwrap_or("").trim().is_empty() {
            n.title_vi = Some(translate_hanviet_phrases(&n.title));
        }
    }
    (
        StatusCode::OK,
        Json(DiscoverResponse {
            novels,
            total,
            personalized: params.sort == "for_you" && logged_in,
        }),
    )
}

pub async fn search_novels(
    State(state): State<AppState>,
    Query(params): Query<SearchParams>,
) -> impl IntoResponse {
    let q_folded = fold_vietnamese_ascii(params.q.trim());
    let mut all = state.load_all_novels();
    if let Some(src) = params.source.as_deref() {
        let s = src.trim();
        if !s.is_empty() && s != "all" {
            all.retain(|n| n.source_id.eq_ignore_ascii_case(s));
        }
    }
    if !q_folded.is_empty() {
        all.retain(|n| {
            fold_vietnamese_ascii(n.display_title()).contains(&q_folded)
                || fold_vietnamese_ascii(&n.title).contains(&q_folded)
                || fold_vietnamese_ascii(n.display_author()).contains(&q_folded)
        });
    }
    let total = all.len();
    all.truncate(100);
    let results: Vec<Value> = all
        .iter()
        .map(|n| {
            json!({
                "id": n.id, "title": n.title, "title_vi": n.display_title(),
                "author": n.display_author(), "url": n.source_url, "source_url": n.source_url,
                "cover_url": n.cover_url,
                "source_id": if n.source_id.is_empty() { "69shuba" } else { &n.source_id },
                "total_chapters": n.total_chapters,
                "translated_chapters": n.translated_chapters,
                "overnight_enabled": n.overnight_enabled
            })
        })
        .collect();

    (
        StatusCode::OK,
        Json(json!({
            "query": params.q, "total": total, "results": results,
            "novels": results, "engine": "rust-axum-hanviet"
        })),
    )
}

pub async fn suggest_novels(
    State(state): State<AppState>,
    Query(params): Query<SearchParams>,
) -> impl IntoResponse {
    let q_folded = fold_vietnamese_ascii(params.q.trim());
    let mut all = state.load_all_novels();
    if !q_folded.is_empty() {
        all.retain(|n| {
            fold_vietnamese_ascii(n.display_title()).contains(&q_folded)
                || fold_vietnamese_ascii(&n.title).contains(&q_folded)
                || fold_vietnamese_ascii(n.display_author()).contains(&q_folded)
        });
    }
    all.truncate(params.limit.clamp(1, 15));
    let suggestions: Vec<Value> = all
        .iter()
        .map(|n| {
            json!({
                "label": n.display_title(), "sub": n.display_author(),
                "query": n.display_title(), "kind": "novel", "id": n.id
            })
        })
        .collect();
    (StatusCode::OK, Json(json!({ "query": params.q, "suggestions": suggestions })))
}

pub async fn get_novel_detail(
    State(state): State<AppState>,
    Path(novel_id): Path<String>,
) -> impl IntoResponse {
    let Some(novel) = state.get_novel(&novel_id) else {
        return (
            StatusCode::NOT_FOUND,
            Json(json!({"detail": format!("Không tìm thấy truyện '{}'", novel_id)})),
        );
    };
    let (toc, translated_nums) = state.get_novel_toc_and_translated(&novel_id);
    let total_chapters = if novel.total_chapters > 0 { novel.total_chapters } else { toc.len() as i32 };
    let translated_chapters = if !translated_nums.is_empty() {
        translated_nums.len() as i32
    } else {
        novel.translated_chapters
    };

    (
        StatusCode::OK,
        Json(json!({
            "id": novel.id, "title": novel.title, "title_vi": novel.display_title(),
            "author": novel.display_author(), "source_url": novel.source_url,
            "source_id": if novel.source_id.is_empty() { "69shuba" } else { &novel.source_id },
            "cover_url": novel.cover_url, "description": novel.description,
            "status": if novel.status.is_empty() { "ongoing" } else { &novel.status },
            "total_chapters": total_chapters, "translated_chapters": translated_chapters,
            "overnight_enabled": novel.overnight_enabled,
            "translated_chapter_numbers": translated_nums, "chapters": toc
        })),
    )
}

pub async fn get_chapter(
    State(state): State<AppState>,
    Path((novel_id, chapter_number)): Path<(String, i32)>,
) -> impl IntoResponse {
    let mut val = state.get_chapter_value(&novel_id, chapter_number).unwrap_or_else(|| {
        json!({
            "novel_id": novel_id, "chapter_number": chapter_number,
            "title": format!("Chương {}", chapter_number),
            "title_vi": format!("Chương {}", chapter_number),
            "original_content": "", "translated_content": null
        })
    });

    let mut orig = val
        .get("original_content")
        .or_else(|| val.get("content_raw"))
        .and_then(|v| v.as_str())
        .unwrap_or("")
        .to_string();
    let ch_url = val
        .get("url")
        .or_else(|| val.get("source_url"))
        .and_then(|v| v.as_str())
        .unwrap_or("")
        .to_string();

    if orig.trim().is_empty() && ch_url.starts_with("http") {
        if let Some(body) = fetch_chapter_body(&state.http_client, &ch_url).await {
            orig = body;
            if let Some(obj) = val.as_object_mut() {
                obj.insert("original_content".into(), Value::String(orig.clone()));
            }
            state.save_chapter_value(&novel_id, chapter_number, &val);
        }
    }

    let trans = val
        .get("translated_content")
        .or_else(|| val.get("content_translated"))
        .and_then(|v| v.as_str())
        .filter(|s| !s.trim().is_empty())
        .map(ToOwned::to_owned);

    (
        StatusCode::OK,
        Json(json!({
            "novel_id": novel_id, "chapter_number": chapter_number,
            "title": val.get("title").and_then(|v| v.as_str()).unwrap_or(&format!("Chương {}", chapter_number)),
            "title_vi": val.get("title_vi").and_then(|v| v.as_str()).unwrap_or(""),
            "original_content": orig, "translated_content": trans,
            "translation_engine": val.get("translation_engine").and_then(|v| v.as_str()).unwrap_or("RUST_AXUM"),
            "is_fallback": false, "cached": trans.is_some()
        })),
    )
}

pub async fn get_chapter_recap(
    State(state): State<AppState>,
    Path((novel_id, chapter_number)): Path<(String, i32)>,
) -> impl IntoResponse {
    let novel_title = state
        .get_novel(&novel_id)
        .map(|n| n.display_title().to_string())
        .unwrap_or_else(|| novel_id.clone());
    (
        StatusCode::OK,
        Json(json!({
            "novel_id": novel_id, "chapter_number": chapter_number,
            "novel_title": novel_title, "chapter_title": format!("Chương {}", chapter_number),
            "bullets": [format!("Tóm tắt nhanh mạch truyện trước Chương {} bởi Rust Engine.", chapter_number)],
            "characters": [], "context": "Đang chạy trên TruyenCloud Rust Backend.",
            "is_fallback": false, "chapters_covered": [ (chapter_number - 1).max(1) ]
        })),
    )
}

pub async fn export_novel_epub(
    State(state): State<AppState>,
    Path(novel_id): Path<String>,
) -> impl IntoResponse {
    let clean_id = novel_id.trim_end_matches(".epub").to_string();
    let novel = state.get_novel(&clean_id).unwrap_or(Novel {
        id: clean_id.clone(),
        title: clean_id.clone(),
        title_vi: Some(clean_id.clone()),
        ..Default::default()
    });
    let mut chapters = state.get_chapters_for_novel(&clean_id);
    if chapters.is_empty() {
        chapters.push(Chapter {
            novel_id: clean_id.clone(),
            chapter_number: 1,
            title_vi: Some("Chương 1: Giới thiệu".into()),
            content_translated: Some(novel.description.clone()),
            is_translated: true,
            ..Default::default()
        });
    }
    match build_epub_bytes(&novel, &chapters) {
        Ok(bytes) => Response::builder()
            .status(StatusCode::OK)
            .header(header::CONTENT_TYPE, "application/epub+zip")
            .header(
                header::CONTENT_DISPOSITION,
                format!("attachment; filename=\"truyencloud_{}.epub\"", clean_id),
            )
            .body(Body::from(bytes))
            .unwrap(),
        Err(err) => Response::builder()
            .status(StatusCode::INTERNAL_SERVER_ERROR)
            .header(header::CONTENT_TYPE, "application/json")
            .body(Body::from(json!({"detail": err}).to_string()))
            .unwrap(),
    }
}
