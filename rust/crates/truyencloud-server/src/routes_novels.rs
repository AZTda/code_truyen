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
use truyencloud_core::models::{Chapter, DiscoverResponse, GlossaryMap, Novel};

#[derive(Debug, Deserialize)]
pub struct DiscoverParams {
    #[serde(default = "default_sort")]
    pub sort: String,
    #[serde(default = "default_limit")]
    pub limit: usize,
}

fn default_sort() -> String {
    "recent".to_string()
}

fn default_limit() -> usize {
    500
}

#[derive(Debug, Deserialize)]
pub struct SearchParams {
    #[serde(default)]
    pub q: String,
}

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
        "popular" => {
            novels.sort_by(|a, b| {
                b.translated_chapters
                    .cmp(&a.translated_chapters)
                    .then_with(|| b.total_chapters.cmp(&a.total_chapters))
            });
        }
        "for_you" => {
            novels.sort_by(|a, b| {
                b.overnight_enabled
                    .cmp(&a.overnight_enabled)
                    .then_with(|| b.translated_chapters.cmp(&a.translated_chapters))
            });
        }
        _ => {}
    }

    let limit = params.limit.clamp(1, 500);
    novels.truncate(limit);

    for n in &mut novels {
        if n.title_vi.as_deref().unwrap_or("").trim().is_empty() {
            n.title_vi = Some(translate_hanviet_phrases(&n.title));
        }
    }

    let resp = DiscoverResponse {
        novels,
        total,
        personalized: params.sort == "for_you" && logged_in,
    };
    (StatusCode::OK, Json(resp))
}

pub async fn search_novels(
    State(state): State<AppState>,
    Query(params): Query<SearchParams>,
) -> impl IntoResponse {
    let q_folded = fold_vietnamese_ascii(params.q.trim());
    let mut all = state.load_all_novels();
    if !q_folded.is_empty() {
        all.retain(|n| {
            let title_folded = fold_vietnamese_ascii(n.display_title());
            let raw_folded = fold_vietnamese_ascii(&n.title);
            let author_folded = fold_vietnamese_ascii(n.display_author());
            title_folded.contains(&q_folded)
                || raw_folded.contains(&q_folded)
                || author_folded.contains(&q_folded)
        });
    }
    let total = all.len();
    all.truncate(100);
    (
        StatusCode::OK,
        Json(json!({
            "novels": all,
            "total": total,
            "query": params.q,
            "engine": "rust-axum-hanviet"
        })),
    )
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
    let chapters = state.get_chapters_for_novel(&novel_id);
    let toc: Vec<Value> = chapters
        .iter()
        .map(|c| {
            json!({
                "chapter_number": c.chapter_number,
                "title": c.title,
                "title_vi": c.title_vi,
                "is_translated": c.is_translated || c.content_translated.as_deref().map(|s| !s.is_empty()).unwrap_or(false),
            })
        })
        .collect();

    let mut body = serde_json::to_value(&novel).unwrap_or_default();
    if let Some(obj) = body.as_object_mut() {
        obj.insert("chapters".into(), Value::Array(toc));
    }
    (StatusCode::OK, Json(body))
}

pub async fn get_chapter(
    State(state): State<AppState>,
    Path((novel_id, chapter_number)): Path<(String, i32)>,
) -> impl IntoResponse {
    let chapters = state.get_chapters_for_novel(&novel_id);
    if let Some(ch) = chapters.into_iter().find(|c| c.chapter_number == chapter_number) {
        return (StatusCode::OK, Json(serde_json::to_value(ch).unwrap_or_default()));
    }
    (
        StatusCode::NOT_FOUND,
        Json(json!({"detail": format!("Chưa có chương {} của truyện {}", chapter_number, novel_id)})),
    )
}

pub async fn export_novel_epub(
    State(state): State<AppState>,
    Path(novel_id): Path<String>,
) -> impl IntoResponse {
    let novel = state.get_novel(&novel_id).unwrap_or(Novel {
        id: novel_id.clone(),
        title: novel_id.clone(),
        title_vi: Some(novel_id.clone()),
        ..Default::default()
    });
    let mut chapters = state.get_chapters_for_novel(&novel_id);
    if chapters.is_empty() {
        chapters.push(Chapter {
            novel_id: novel_id.clone(),
            chapter_number: 1,
            title_vi: Some("Chương 1: Giới thiệu".into()),
            content_translated: Some(novel.description.clone()),
            is_translated: true,
            ..Default::default()
        });
    }

    match build_epub_bytes(&novel, &chapters) {
        Ok(bytes) => {
            let safe_name: String = novel
                .id
                .chars()
                .filter(|c| c.is_alphanumeric() || *c == '_' || *c == '-')
                .collect();
            Response::builder()
                .status(StatusCode::OK)
                .header(header::CONTENT_TYPE, "application/epub+zip")
                .header(
                    header::CONTENT_DISPOSITION,
                    format!("attachment; filename=\"{}.epub\"", safe_name),
                )
                .body(Body::from(bytes))
                .unwrap()
        }
        Err(err) => Response::builder()
            .status(StatusCode::INTERNAL_SERVER_ERROR)
            .header(header::CONTENT_TYPE, "application/json")
            .body(Body::from(json!({"detail": err}).to_string()))
            .unwrap(),
    }
}

pub async fn get_glossary(
    State(state): State<AppState>,
    Path(novel_id): Path<String>,
) -> impl IntoResponse {
    let terms = state.load_glossary(&novel_id);
    (
        StatusCode::OK,
        Json(json!({
            "novel_id": novel_id,
            "terms": terms,
            "total": terms.len()
        })),
    )
}

#[derive(Debug, Deserialize)]
pub struct UpdateGlossaryPayload {
    pub terms: GlossaryMap,
}

pub async fn put_glossary(
    State(state): State<AppState>,
    Path(novel_id): Path<String>,
    Json(payload): Json<UpdateGlossaryPayload>,
) -> impl IntoResponse {
    match state.save_glossary(&novel_id, &payload.terms) {
        Ok(()) => (
            StatusCode::OK,
            Json(json!({
                "status": "ok",
                "novel_id": novel_id,
                "total": payload.terms.len()
            })),
        ),
        Err(e) => (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(json!({"detail": e})),
        ),
    }
}
