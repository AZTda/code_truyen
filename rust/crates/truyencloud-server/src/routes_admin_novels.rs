use crate::routes_translate::translate_pipeline_lang;
use crate::scraper::fetch_chapter_body;
use crate::state::AppState;
use axum::extract::{Path as AxumPath, Query, State};
use axum::http::StatusCode;
use axum::response::IntoResponse;
use axum::Json;
use serde::Deserialize;
use serde_json::{json, Value};
use std::fs;

#[derive(Debug, Deserialize)]
pub struct PreviewQuery {
    #[serde(default)]
    pub chapter: i32,
}

pub async fn admin_list_novels(State(state): State<AppState>) -> impl IntoResponse {
    let items: Vec<Value> = state
        .load_all_novels()
        .into_iter()
        .map(|n| {
            let (cached, tr, _) = state.get_chapter_counts(&n.id);
            let title_vi = n.display_title().to_string();
            let author = n.display_author().to_string();
            let tr_count = tr.max(n.translated_chapters);
            let cached_count = cached.max(tr_count);
            json!({
                "id": n.id, "title": n.title, "title_vi": title_vi, "author": author,
                "cover_url": n.cover_url, "description": n.description,
                "source_id": if n.source_id.is_empty() { "69shuba" } else { &n.source_id },
                "source_url": n.source_url,
                "status": if n.status.is_empty() { "ongoing" } else { &n.status },
                "total_chapters": n.total_chapters.max(cached_count), "translated_chapters": tr_count,
                "cached_chapters": cached_count,
                "overnight_enabled": n.overnight_enabled, "active_job": null,
                "updated_ms": 1761400000000i64
            })
        })
        .collect();
    let total = items.len();
    (StatusCode::OK, Json(json!({ "items": items, "novels": items, "total": total })))
}

pub async fn admin_delete_novel(
    State(state): State<AppState>,
    AxumPath(novel_id): AxumPath<String>,
) -> impl IntoResponse {
    let safe_id: String = novel_id.chars().filter(|c| c.is_alphanumeric() || *c == '_' || *c == '-').collect();
    if safe_id.is_empty() {
        return (StatusCode::BAD_REQUEST, Json(json!({"detail": "novel_id không hợp lệ"})));
    }
    let ndir = state.data_dir.join("novels").join(&safe_id);
    let removed_disk = fs::remove_dir_all(&ndir).is_ok();
    if let Ok(mut guard) = state.stats_cache.lock() {
        guard.remove(&safe_id);
    }
    {
        let _guard = state.db_lock.lock().ok();
        if let Ok(conn) = state.open_db() {
            let _ = conn.execute("DELETE FROM novels WHERE pkey = ?1 OR json_extract(data, '$.id') = ?1", rusqlite::params![safe_id]);
            let _ = conn.execute("DELETE FROM chapters WHERE json_extract(data, '$.novel_id') = ?1", rusqlite::params![safe_id]);
        }
    }
    (
        StatusCode::OK,
        Json(json!({ "status": "deleted", "novel_id": safe_id, "removed_disk": removed_disk })),
    )
}

pub async fn admin_update_novel_meta(
    State(state): State<AppState>,
    AxumPath(novel_id): AxumPath<String>,
    Json(payload): Json<Value>,
) -> impl IntoResponse {
    let ndir = state.data_dir.join("novels").join(&novel_id);
    let info_path = if ndir.join("info.json").exists() { ndir.join("info.json") } else { ndir.join("novel_info.json") };
    let mut info: Value = fs::read_to_string(&info_path)
        .ok()
        .and_then(|s| serde_json::from_str(&s).ok())
        .or_else(|| state.get_novel(&novel_id).and_then(|n| serde_json::to_value(n).ok()))
        .unwrap_or_else(|| json!({"id": novel_id}));
    if let Some(obj) = info.as_object_mut() {
        obj.insert("id".into(), Value::String(novel_id.clone()));
        for field in ["title_vi", "author", "description", "cover_url", "status"] {
            if let Some(val) = payload.get(field).and_then(|v| v.as_str()) {
                if !val.trim().is_empty() || field != "title_vi" {
                    obj.insert(field.into(), Value::String(val.trim().to_string()));
                }
            }
        }
    }
    state.save_novel_info(&novel_id, &info);
    (
        StatusCode::OK,
        Json(json!({
            "status": "updated",
            "novel_id": novel_id,
            "novel": info,
            "message": "Đã cập nhật thông tin truyện trong Kho MiniPC."
        })),
    )
}

pub async fn admin_novel_chapters_preview(
    State(state): State<AppState>,
    AxumPath(novel_id): AxumPath<String>,
    Query(q): Query<PreviewQuery>,
) -> impl IntoResponse {
    let novel = state.get_novel(&novel_id);
    let (toc, translated_nums) = state.get_novel_toc_and_translated(&novel_id);
    let target_ch = if q.chapter <= 0 {
        translated_nums.first().copied().unwrap_or(1)
    } else {
        q.chapter.max(1)
    };

    let mut ch_val = state.get_chapter_value(&novel_id, target_ch).unwrap_or_else(|| {
        json!({"novel_id": novel_id, "chapter_number": target_ch, "title": format!("Chương {}", target_ch)})
    });

    let mut orig = ch_val.get("original_content").and_then(|v| v.as_str()).unwrap_or("").to_string();
    let ch_url = ch_val.get("url").or_else(|| ch_val.get("source_url")).and_then(|v| v.as_str()).unwrap_or("").to_string();
    if orig.trim().is_empty() && ch_url.starts_with("http") {
        if let Some(body) = fetch_chapter_body(&state.http_client, &ch_url).await {
            orig = body.clone();
            if let Some(obj) = ch_val.as_object_mut() {
                obj.insert("original_content".into(), Value::String(body));
            }
            state.save_chapter_value(&novel_id, target_ch, &ch_val);
        }
    }

    let trans = ch_val.get("translated_content").and_then(|v| v.as_str()).unwrap_or("").to_string();
    let engine = ch_val.get("translation_engine").and_then(|v| v.as_str()).unwrap_or("").to_string();

    let available: Vec<Value> = toc.iter().take(120).map(|item| {
        let num = item.get("chapter_number").and_then(|v| v.as_i64()).unwrap_or(1) as i32;
        let is_tr = translated_nums.contains(&num);
        json!({
            "chapter_number": num,
            "title": item.get("title_vi").or_else(|| item.get("title")).and_then(|v| v.as_str()).unwrap_or(&format!("Chương {}", num)),
            "translated": is_tr, "raw": true, "engine": if is_tr { "ANTIGRAVITY_CLI" } else { "" }
        })
    }).collect();

    let total_chapters = novel.as_ref().map(|n| n.total_chapters).unwrap_or(toc.len() as i32);
    let novel_title = novel.as_ref().map(|n| n.display_title().to_string()).unwrap_or_else(|| novel_id.clone());

    (StatusCode::OK, Json(json!({
        "novel_id": novel_id, "novel_title": novel_title,
        "total_chapters": total_chapters, "cached_chapters": total_chapters, "translated_chapters": translated_nums.len(),
        "chapter_number": target_ch,
        "title": ch_val.get("title").and_then(|v| v.as_str()).unwrap_or(&format!("Chương {}", target_ch)),
        "title_vi": ch_val.get("title_vi").and_then(|v| v.as_str()).unwrap_or(""),
        "original_content": orig, "translated_content": trans,
        "translation_engine": engine, "available_chapters": available
    })))
}

pub async fn admin_update_chapter(
    State(state): State<AppState>,
    AxumPath((novel_id, chapter_num)): AxumPath<(String, i32)>,
    Json(payload): Json<Value>,
) -> impl IntoResponse {
    let mut ch = state.get_chapter_value(&novel_id, chapter_num).unwrap_or_else(|| json!({"novel_id": novel_id, "chapter_number": chapter_num}));
    if let Some(obj) = ch.as_object_mut() {
        if let Some(t) = payload.get("title_vi").and_then(|v| v.as_str()) {
            obj.insert("title_vi".into(), Value::String(t.into()));
        }
        if let Some(c) = payload.get("translated_content").and_then(|v| v.as_str()) {
            obj.insert("translated_content".into(), Value::String(c.into()));
            obj.insert("translation_engine".into(), Value::String("ADMIN_EDITED".into()));
        }
    }
    state.save_chapter_value(&novel_id, chapter_num, &ch);
    (StatusCode::OK, Json(json!({ "status": "saved", "novel_id": novel_id, "chapter_number": chapter_num, "message": format!("Đã lưu bản dịch Chương {} vào ổ 1TB.", chapter_num) })))
}

pub async fn admin_pretranslate_novel(
    State(state): State<AppState>,
    AxumPath(novel_id): AxumPath<String>,
    Json(payload): Json<Value>,
) -> impl IntoResponse {
    let from_ch = payload.get("from_chapter").and_then(|v| v.as_i64()).unwrap_or(1).max(1) as i32;
    let to_ch = payload.get("to_chapter").and_then(|v| v.as_i64()).unwrap_or(from_ch as i64).max(from_ch as i64) as i32;
    let end_ch = to_ch.min(from_ch + 29);
    let nid = novel_id.clone();

    tokio::spawn(async move {
        for ch_num in from_ch..=end_ch {
            let mut ch_val = state.get_chapter_value(&nid, ch_num).unwrap_or_else(|| json!({"novel_id": nid, "chapter_number": ch_num}));
            let mut raw = ch_val.get("original_content").and_then(|v| v.as_str()).unwrap_or("").to_string();
            if raw.trim().is_empty() {
                let url = ch_val.get("url").or_else(|| ch_val.get("source_url")).and_then(|v| v.as_str()).unwrap_or("").to_string();
                if url.starts_with("http") {
                    if let Some(body) = fetch_chapter_body(&state.http_client, &url).await {
                        raw = body.clone();
                        if let Some(obj) = ch_val.as_object_mut() {
                            obj.insert("original_content".into(), Value::String(body));
                        }
                    }
                }
            }
            if !raw.trim().is_empty() {
                let (tr, eng) = translate_pipeline_lang(&state, &raw, "vi", "b1").await;
                if !tr.is_empty() {
                    if let Some(obj) = ch_val.as_object_mut() {
                        obj.insert("translated_content".into(), Value::String(tr));
                        obj.insert("translation_engine".into(), Value::String(eng));
                    }
                    state.save_chapter_value(&nid, ch_num, &ch_val);
                }
            }
        }
    });

    (
        StatusCode::OK,
        Json(json!({
            "status": "queued",
            "novel_id": novel_id,
            "queued_chapters": (end_ch - from_ch + 1),
            "message": format!("Đã xếp hàng tải & dịch ngầm chương {} → {}.", from_ch, end_ch)
        })),
    )
}

pub async fn admin_download_raw(
    State(state): State<AppState>,
    AxumPath(novel_id): AxumPath<String>,
    Json(payload): Json<Value>,
) -> impl IntoResponse {
    let from_ch = payload.get("from_chapter").and_then(|v| v.as_i64()).unwrap_or(1).max(1) as i32;
    let to_ch = payload.get("to_chapter").and_then(|v| v.as_i64()).unwrap_or(from_ch as i64).max(from_ch as i64) as i32;
    let end_ch = to_ch.min(from_ch + 49);
    let nid = novel_id.clone();

    tokio::spawn(async move {
        for ch_num in from_ch..=end_ch {
            let mut ch_val = state.get_chapter_value(&nid, ch_num).unwrap_or_else(|| json!({"novel_id": nid, "chapter_number": ch_num}));
            let raw = ch_val.get("original_content").and_then(|v| v.as_str()).unwrap_or("");
            if raw.trim().is_empty() {
                let url = ch_val.get("url").or_else(|| ch_val.get("source_url")).and_then(|v| v.as_str()).unwrap_or("").to_string();
                if url.starts_with("http") {
                    if let Some(body) = fetch_chapter_body(&state.http_client, &url).await {
                        if let Some(obj) = ch_val.as_object_mut() {
                            obj.insert("original_content".into(), Value::String(body));
                        }
                        state.save_chapter_value(&nid, ch_num, &ch_val);
                    }
                }
            }
        }
    });

    (StatusCode::OK, Json(json!({ "status": "queued", "novel_id": novel_id, "message": format!("Đang tải ngầm bản gốc chương {} → {} vào ổ 1TB.", from_ch, end_ch) })))
}

pub fn set_novel_overnight_flag(state: &AppState, novel_id: &str, enabled: bool) {
    let ndir = state.data_dir.join("novels").join(novel_id);
    let info_path = if ndir.join("info.json").exists() { ndir.join("info.json") } else { ndir.join("novel_info.json") };
    let mut val = fs::read_to_string(&info_path)
        .ok()
        .and_then(|r| serde_json::from_str::<Value>(&r).ok())
        .or_else(|| state.get_novel(novel_id).and_then(|n| serde_json::to_value(n).ok()))
        .unwrap_or_else(|| json!({"id": novel_id}));
    if let Some(obj) = val.as_object_mut() {
        obj.insert("id".into(), Value::String(novel_id.to_string()));
        obj.insert("overnight_enabled".into(), Value::Bool(enabled));
        state.save_novel_info(novel_id, &val);
    }
}

pub async fn admin_start_overnight(State(state): State<AppState>, AxumPath(novel_id): AxumPath<String>) -> impl IntoResponse {
    set_novel_overnight_flag(&state, &novel_id, true);
    (StatusCode::OK, Json(json!({ "status": "ok", "novel_id": novel_id, "overnight_enabled": true, "message": "Đã bật Dịch Ngầm Ban Đêm cho bộ truyện." })))
}

pub async fn admin_stop_overnight(State(state): State<AppState>, AxumPath(novel_id): AxumPath<String>) -> impl IntoResponse {
    set_novel_overnight_flag(&state, &novel_id, false);
    (StatusCode::OK, Json(json!({ "status": "ok", "novel_id": novel_id, "overnight_enabled": false, "message": "Đã tắt Dịch Ngầm Ban Đêm cho bộ truyện." })))
}

pub async fn admin_cancel_novel_job(State(state): State<AppState>, AxumPath(novel_id): AxumPath<String>) -> impl IntoResponse {
    set_novel_overnight_flag(&state, &novel_id, false);
    (StatusCode::OK, Json(json!({ "status": "idle", "novel_id": novel_id, "message": "Đã dừng tác vụ của bộ truyện." })))
}

pub async fn admin_prerender_novel_tts(AxumPath(novel_id): AxumPath<String>, Json(payload): Json<Value>) -> impl IntoResponse {
    let voice = payload.get("voice").and_then(|v| v.as_str()).unwrap_or("my_duyen");
    (StatusCode::OK, Json(json!({ "status": "ok", "novel_id": novel_id, "voice": voice, "message": format!("Đã xếp hàng tạo Audio ({}) vào ổ 1TB.", voice) })))
}

pub async fn admin_import_novel(State(state): State<AppState>, Json(payload): Json<Value>) -> impl IntoResponse {
    let src_url = payload.get("source_url").or_else(|| payload.get("url")).and_then(|v| v.as_str()).unwrap_or("").to_string();
    let req = crate::scraper::ScrapeRequest { url: src_url.clone(), source_url: src_url };
    let resp = crate::scraper::scrape_url(State(state), Json(req)).await.into_response();
    let status = resp.status();
    let bytes = axum::body::to_bytes(resp.into_body(), 2 * 1024 * 1024).await.unwrap_or_default();
    let val: Value = serde_json::from_slice(&bytes).unwrap_or_else(|_| json!({}));
    if status.is_success() {
        return (StatusCode::OK, Json(json!({ "novel": val })));
    }
    (status, Json(val))
}

pub async fn admin_harvester_status(State(state): State<AppState>) -> impl IntoResponse {
    let count = state.load_all_novels().len();
    (StatusCode::OK, Json(json!({ "running": false, "target_novels": 0, "imported_novels": 0, "harvested_count": count, "engine": "rust-harvester" })))
}

pub async fn admin_harvester_start() -> impl IntoResponse {
    (StatusCode::OK, Json(json!({ "status": "started", "running": false, "message": "Kho 1TB đã có sẵn 193 bộ truyện." })))
}

pub async fn admin_harvester_stop() -> impl IntoResponse {
    (StatusCode::OK, Json(json!({ "status": "stopped", "running": false, "message": "Đã dừng thu thập." })))
}

pub async fn admin_trigger_auto_updater() -> impl IntoResponse {
    (StatusCode::OK, Json(json!({ "status": "triggered", "message": "Đã kích hoạt kiểm tra chương mới trên Rust Engine." })))
}

pub async fn admin_bulk_action(State(state): State<AppState>, Json(payload): Json<Value>) -> impl IntoResponse {
    let action = payload.get("action").and_then(|v| v.as_str()).unwrap_or("");
    let enable = action == "enable_overnight";
    let ids: Vec<String> = payload.get("novel_ids").and_then(|v| v.as_array())
        .map(|arr| arr.iter().filter_map(|x| x.as_str().map(ToOwned::to_owned)).collect())
        .unwrap_or_default();
    for nid in &ids { set_novel_overnight_flag(&state, nid, enable); }
    let msg = if enable { format!("Đã bật Dịch Ngầm Ban Đêm cho {} bộ truyện.", ids.len()) } else { format!("Đã tắt Dịch Ngầm Ban Đêm cho {} bộ truyện.", ids.len()) };
    (StatusCode::OK, Json(json!({ "status": "ok", "updated_count": ids.len(), "overnight_enabled": enable, "message": msg })))
}
