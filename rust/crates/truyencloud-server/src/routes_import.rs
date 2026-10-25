use crate::scraper::{decode_html_bytes, parse_chapter_html, scrape_url, ScrapeRequest};
use crate::state::AppState;
use axum::body::Body;
use axum::extract::{FromRequest, Multipart, Request, State};
use axum::http::{header, Response, StatusCode};
use axum::response::{Html, IntoResponse};
use axum::Json;
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::fs;
use std::io::{Cursor, Read};
use truyencloud_core::text_paging::clean_paragraphs;

pub async fn serve_download_html(State(state): State<AppState>) -> impl IntoResponse {
    if let Ok(content) = fs::read_to_string(state.static_dir.join("download.html")) {
        return Html(content);
    }
    Html("<!DOCTYPE html><html><head><meta charset='utf-8'><title>TruyenCloud</title></head><body><h1>TruyenCloud</h1><p><a href='/v1/app/download-latest'>Tải APK mới nhất</a></p></body></html>".to_string())
}

pub async fn download_latest_apk(State(state): State<AppState>) -> impl IntoResponse {
    let apk_path = state.static_dir.join("truyencloud-latest.apk");
    if let Ok(bytes) = fs::read(&apk_path) {
        if !bytes.is_empty() {
            return Response::builder()
                .status(StatusCode::OK)
                .header(header::CONTENT_TYPE, "application/vnd.android.package-archive")
                .header(
                    header::CONTENT_DISPOSITION,
                    "attachment; filename=\"TruyenCloud-latest.apk\"",
                )
                .header(header::CONTENT_LENGTH, bytes.len().to_string())
                .body(Body::from(bytes))
                .unwrap();
        }
    }
    Response::builder()
        .status(StatusCode::NOT_FOUND)
        .header(header::CONTENT_TYPE, "application/json")
        .body(Body::from(r#"{"detail":"Chưa có tệp APK trên máy chủ."}"#))
        .unwrap()
}

pub async fn admin_upload_apk(
    State(state): State<AppState>,
    mut multipart: Multipart,
) -> impl IntoResponse {
    let mut apk_bytes: Vec<u8> = Vec::new();
    let mut filename = String::new();

    while let Ok(Some(field)) = multipart.next_field().await {
        if field.name() == Some("file") {
            filename = field.file_name().unwrap_or("truyencloud-latest.apk").to_string();
            if let Ok(data) = field.bytes().await {
                apk_bytes = data.to_vec();
            }
        }
    }

    if apk_bytes.len() < 1024 || !filename.to_ascii_lowercase().ends_with(".apk") {
        return (
            StatusCode::BAD_REQUEST,
            Json(json!({"detail": "Tệp tải lên phải là tệp .apk hợp lệ."})),
        );
    }

    let mut hasher = Sha256::new();
    hasher.update(&apk_bytes);
    let sha256_hex = hex::encode(hasher.finalize());
    let file_size = apk_bytes.len() as u64;

    let _ = fs::create_dir_all(&state.static_dir);
    let apk_path = state.static_dir.join("truyencloud-latest.apk");
    if let Err(e) = fs::write(&apk_path, &apk_bytes) {
        return (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(json!({"detail": format!("Lỗi ghi tệp APK: {}", e)})),
        );
    }

    let version_file = state.data_dir.join("app_version.json");
    let mut info: Value = fs::read_to_string(&version_file)
        .ok()
        .and_then(|s| serde_json::from_str(&s).ok())
        .unwrap_or_else(|| {
            json!({
                "version_code": 21,
                "version_name": "1.6.1-rust",
                "min_version_code": 1,
                "download_url": "/static/truyencloud-latest.apk",
                "changelog": "TruyenCloud APK cập nhật mới",
                "is_mandatory": false
            })
        });
    if let Some(obj) = info.as_object_mut() {
        obj.insert("sha256".into(), Value::String(sha256_hex.clone()));
        obj.insert("file_size_bytes".into(), json!(file_size));
        obj.insert("apk_exists".into(), Value::Bool(true));
        obj.insert("download_url".into(), Value::String("/static/truyencloud-latest.apk".into()));
        obj.insert("release_date".into(), Value::String(chrono::Utc::now().format("%Y-%m-%d").to_string()));
    }
    let _ = fs::write(&version_file, serde_json::to_string_pretty(&info).unwrap_or_default());

    (
        StatusCode::OK,
        Json(json!({
            "status": "ok",
            "version": info,
            "sha256": sha256_hex,
            "file_size_bytes": file_size,
            "message": "Đã tải lên tệp APK và cập nhật SHA-256 thành công."
        })),
    )
}

pub async fn import_novel_flexible(
    State(state): State<AppState>,
    req: Request,
) -> Response<Body> {
    let ct = req
        .headers()
        .get(header::CONTENT_TYPE)
        .and_then(|v| v.to_str().ok())
        .unwrap_or("")
        .to_ascii_lowercase();

    if ct.starts_with("multipart/form-data") {
        let Ok(multipart) = Multipart::from_request(req, &state).await else {
            return (
                StatusCode::BAD_REQUEST,
                Json(json!({"detail": "Dữ liệu multipart không hợp lệ."})),
            )
                .into_response();
        };
        return import_multipart_novel(state, multipart).await.into_response();
    }

    let Ok(bytes) = axum::body::to_bytes(req.into_body(), 10 * 1024 * 1024).await else {
        return (StatusCode::BAD_REQUEST, Json(json!({"detail": "Body quá lớn hoặc lỗi đọc."}))).into_response();
    };
    let Ok(scrape_req) = serde_json::from_slice::<ScrapeRequest>(&bytes) else {
        return (StatusCode::BAD_REQUEST, Json(json!({"detail": "JSON yêu cầu không hợp lệ."}))).into_response();
    };
    scrape_url(State(state), Json(scrape_req)).await.into_response()
}

async fn import_multipart_novel(state: AppState, mut multipart: Multipart) -> impl IntoResponse {
    let mut title = String::new();
    let mut author = String::from("Khuyết danh");
    let mut filename = String::from("novel.txt");
    let mut file_bytes: Vec<u8> = Vec::new();

    while let Ok(Some(field)) = multipart.next_field().await {
        match field.name() {
            Some("title") => {
                if let Ok(t) = field.text().await {
                    if !t.trim().is_empty() { title = t.trim().to_string(); }
                }
            }
            Some("author") => {
                if let Ok(a) = field.text().await {
                    if !a.trim().is_empty() { author = a.trim().to_string(); }
                }
            }
            Some("file") => {
                if let Some(fnm) = field.file_name() {
                    filename = fnm.to_string();
                }
                if let Ok(b) = field.bytes().await {
                    file_bytes = b.to_vec();
                }
            }
            _ => {}
        }
    }

    if file_bytes.is_empty() {
        return (StatusCode::BAD_REQUEST, Json(json!({"detail": "Tệp tải lên trống."})));
    }
    if title.is_empty() {
        title = filename
            .trim_end_matches(".txt")
            .trim_end_matches(".epub")
            .replace(['_', '-'], " ")
            .trim()
            .to_string();
        if title.is_empty() { title = "Truyện tải lên".to_string(); }
    }

    let chapters_list = if filename.to_ascii_lowercase().ends_with(".epub") {
        extract_epub_chapters(&file_bytes)
    } else {
        let decoded = decode_html_bytes(&file_bytes, None);
        split_txt_into_chapters(&decoded)
    };

    if chapters_list.is_empty() {
        return (StatusCode::BAD_REQUEST, Json(json!({"detail": "Không trích xuất được chương nào từ tệp."})));
    }

    let novel_id = format!("local_{}", &uuid::Uuid::new_v4().simple().to_string()[..10]);
    let total_chapters = chapters_list.len() as i32;
    let desc: String = chapters_list[0].1.chars().take(240).collect();

    let mut toc = Vec::new();
    for (idx, (ch_title, ch_body)) in chapters_list.iter().enumerate() {
        let ch_num = (idx + 1) as i32;
        let ch_json = json!({
            "novel_id": novel_id,
            "chapter_number": ch_num,
            "title": ch_title,
            "title_vi": ch_title,
            "original_content": ch_body,
            "translated_content": ch_body,
            "translation_engine": "UPLOADED_FILE",
            "url": ""
        });
        state.save_chapter_value(&novel_id, ch_num, &ch_json);
        toc.push(json!({
            "chapter_number": ch_num,
            "id": ch_num.to_string(),
            "title": ch_title,
            "title_vi": ch_title,
            "url": ""
        }));
    }

    let novel_dir = state.data_dir.join("novels").join(&novel_id);
    let _ = fs::create_dir_all(&novel_dir);
    let _ = fs::write(
        novel_dir.join("chapters_index.json"),
        serde_json::to_string_pretty(&toc).unwrap_or_default(),
    );

    let info = json!({
        "id": novel_id,
        "title": title,
        "title_vi": title,
        "author": author,
        "cover_url": "",
        "description": desc,
        "source_id": "upload",
        "source_url": format!("upload://{}", filename),
        "status": "ongoing",
        "total_chapters": total_chapters,
        "translated_chapters": total_chapters,
        "overnight_enabled": false
    });
    state.save_novel_info(&novel_id, &info);

    (StatusCode::OK, Json(info))
}

fn split_txt_into_chapters(text: &str) -> Vec<(String, String)> {
    let paras = clean_paragraphs(text);
    if paras.is_empty() { return Vec::new(); }
    let mut chapters = Vec::new();
    let mut cur_title = String::from("Chương 1");
    let mut cur_paras = Vec::new();
    let mut cur_chars = 0usize;

    for p in paras {
        let trimmed = p.trim();
        let is_heading = trimmed.chars().count() <= 80
            && (trimmed.starts_with("Chương ")
                || trimmed.starts_with("CHƯƠNG ")
                || trimmed.starts_with("Chapter ")
                || (trimmed.starts_with('第') && (trimmed.contains('章') || trimmed.contains('节') || trimmed.contains('回'))));
        if (is_heading && !cur_paras.is_empty()) || cur_chars >= 4000 {
            let next_num = chapters.len() + 2;
            let body = cur_paras.join("\n\n");
            chapters.push((cur_title, body));
            cur_paras.clear();
            cur_chars = 0;
            cur_title = if is_heading { trimmed.to_string() } else { format!("Chương {}", next_num) };
            if is_heading { continue; }
        } else if is_heading && cur_paras.is_empty() {
            cur_title = trimmed.to_string();
            continue;
        }
        cur_chars += trimmed.chars().count();
        cur_paras.push(trimmed.to_string());
    }
    if !cur_paras.is_empty() {
        chapters.push((cur_title, cur_paras.join("\n\n")));
    }
    chapters
}

fn extract_epub_chapters(bytes: &[u8]) -> Vec<(String, String)> {
    let Ok(mut archive) = zip::ZipArchive::new(Cursor::new(bytes)) else { return Vec::new(); };
    let mut html_names: Vec<String> = (0..archive.len())
        .filter_map(|i| archive.by_index(i).ok().map(|f| f.name().to_string()))
        .filter(|n| {
            let l = n.to_ascii_lowercase();
            (l.ends_with(".xhtml") || l.ends_with(".html") || l.ends_with(".htm"))
                && !l.contains("toc")
                && !l.contains("nav")
                && !l.contains("cover")
        })
        .collect();
    html_names.sort();

    let mut chapters = Vec::new();
    for name in html_names {
        if let Ok(mut file) = archive.by_name(&name) {
            let mut buf = Vec::new();
            if file.read_to_end(&mut buf).is_ok() {
                let html = String::from_utf8_lossy(&buf);
                let (t, body) = parse_chapter_html(&html);
                if body.chars().count() > 60 {
                    let ch_num = chapters.len() + 1;
                    let title = if t.is_empty() { format!("Chương {}", ch_num) } else { t };
                    chapters.push((title, body));
                }
            }
        }
    }
    chapters
}
