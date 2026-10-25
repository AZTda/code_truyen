use crate::scraper::proxy_url_for;
use crate::state::AppState;
use axum::body::Body;
use axum::extract::{Query, State};
use axum::http::{header, Response, StatusCode};
use axum::response::IntoResponse;
use md5::{Digest as Md5Digest, Md5};
use serde::Deserialize;
use std::fs;

#[derive(Debug, Deserialize)]
pub struct ImageProxyQuery {
    #[serde(default)]
    pub url: String,
}

fn md5_hex(input: &str) -> String {
    let mut hasher = Md5::new();
    hasher.update(input.as_bytes());
    hex::encode(hasher.finalize())
}

fn is_blocked_ssrf_url(url: &str) -> bool {
    let lower = url.trim().to_ascii_lowercase();
    if !lower.starts_with("http://") && !lower.starts_with("https://") {
        return true;
    }
    let after_scheme = lower
        .strip_prefix("https://")
        .or_else(|| lower.strip_prefix("http://"))
        .unwrap_or("");
    let host = after_scheme
        .split(['/', ':', '?'])
        .next()
        .unwrap_or("");
    host.is_empty()
        || host == "localhost"
        || host == "0.0.0.0"
        || host == "[::1]"
        || host.starts_with("127.")
        || host.starts_with("10.")
        || host.starts_with("192.168.")
        || host.starts_with("169.254.")
        || host.ends_with(".internal")
        || host.ends_with(".local")
}

fn referer_for_url(url: &str) -> &'static str {
    let lower = url.to_ascii_lowercase();
    if lower.contains("69shuba") || lower.contains("shucdn") || lower.contains("69xinshu") {
        "https://www.69shuba.com/"
    } else if lower.contains("piaotia") || lower.contains("ptwxz") {
        "https://www.piaotia.com/"
    } else if lower.contains("uukanshu") {
        "https://uukanshu.cc/"
    } else {
        "https://www.google.com/"
    }
}

fn build_image_response(bytes: Vec<u8>, content_type: &str, cache_tag: &'static str) -> Response<Body> {
    Response::builder()
        .status(StatusCode::OK)
        .header(header::CONTENT_TYPE, content_type)
        .header(header::CACHE_CONTROL, "public, max-age=604800, immutable")
        .header("X-Cover-Cache", cache_tag)
        .body(Body::from(bytes))
        .unwrap()
}

pub async fn proxy_cover_image(
    State(state): State<AppState>,
    Query(q): Query<ImageProxyQuery>,
) -> impl IntoResponse {
    let raw_url = q.url.trim();
    if is_blocked_ssrf_url(raw_url) {
        return serve_fallback_cover(&state);
    }

    let healed_url = raw_url.replace("cdn.shucdn.com", "static.69shuba.com");
    let cache_dir = state.data_dir.join("covers_cache");
    let raw_hash = md5_hex(raw_url);
    let healed_hash = md5_hex(&healed_url);

    for hash in [&raw_hash, &healed_hash] {
        let img_path = cache_dir.join(format!("{}.img", hash));
        if let Ok(bytes) = fs::read(&img_path) {
            if bytes.len() > 200 {
                let meta_path = cache_dir.join(format!("{}.meta", hash));
                let ct = fs::read_to_string(meta_path)
                    .ok()
                    .map(|s| s.trim().to_string())
                    .filter(|s| s.starts_with("image/"))
                    .unwrap_or_else(|| "image/jpeg".to_string());
                return build_image_response(bytes, &ct, "HIT");
            }
        }
    }

    let referer = referer_for_url(&healed_url);
    let proxied = proxy_url_for(&healed_url);
    for candidate in [healed_url.as_str(), raw_url, proxied.as_str()] {
        let Ok(resp) = state
            .http_client
            .get(candidate)
            .header(reqwest::header::REFERER, referer)
            .send()
            .await
        else {
            continue;
        };
        if !resp.status().is_success() {
            continue;
        }
        let ct = resp
            .headers()
            .get(reqwest::header::CONTENT_TYPE)
            .and_then(|v| v.to_str().ok())
            .filter(|s| s.starts_with("image/"))
            .unwrap_or("image/jpeg")
            .to_string();
        let Ok(bytes) = resp.bytes().await else {
            continue;
        };
        if bytes.len() > 200 {
            let vec_bytes = bytes.to_vec();
            let _ = fs::create_dir_all(&cache_dir);
            let _ = fs::write(cache_dir.join(format!("{}.img", raw_hash)), &vec_bytes);
            let _ = fs::write(cache_dir.join(format!("{}.meta", raw_hash)), &ct);
            return build_image_response(vec_bytes, &ct, "MISS");
        }
    }

    serve_fallback_cover(&state)
}

fn serve_fallback_cover(state: &AppState) -> Response<Body> {
    let fallback_path = state.static_dir.join("fallback_cover.jpg");
    if let Ok(bytes) = fs::read(fallback_path) {
        if !bytes.is_empty() {
            return build_image_response(bytes, "image/jpeg", "FALLBACK");
        }
    }
    Response::builder()
        .status(StatusCode::NOT_FOUND)
        .body(Body::empty())
        .unwrap()
}
