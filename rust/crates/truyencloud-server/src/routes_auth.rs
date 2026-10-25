use crate::state::AppState;
use axum::extract::State;
use axum::http::{HeaderMap, StatusCode};
use axum::response::IntoResponse;
use axum::Json;
use serde::Deserialize;
use serde_json::{json, Value};
use truyencloud_core::crypto::{
    hash_password, issue_token_with_expiry, verify_password, DEFAULT_TTL_SECONDS,
    REFRESH_GRACE_SECONDS,
};
use truyencloud_core::models::{AuthResponse, UserProfile};

#[derive(Debug, Deserialize)]
pub struct LoginRequest {
    pub email: String,
    pub password: String,
}

#[derive(Debug, Deserialize)]
pub struct ChangePasswordRequest {
    pub old_password: String,
    pub new_password: String,
}

pub async fn login(
    State(state): State<AppState>,
    Json(req): Json<LoginRequest>,
) -> impl IntoResponse {
    let ident = req.email.trim().to_lowercase();
    if ident.is_empty() || req.password.is_empty() {
        return (
            StatusCode::BAD_REQUEST,
            Json(json!({"detail": "Vui lòng nhập đầy đủ tên đăng nhập/email và mật khẩu."})),
        );
    }

    let users = state.load_users();
    let matched = users.iter().find_map(|(key, u)| {
        let usr = u.get("username").and_then(|v| v.as_str()).unwrap_or(key);
        let mail = u.get("email").and_then(|v| v.as_str()).unwrap_or("");
        if key.to_lowercase() == ident
            || usr.to_lowercase() == ident
            || mail.to_lowercase() == ident
        {
            Some((usr.to_string(), u.clone()))
        } else {
            None
        }
    });

    let Some((username, record)) = matched else {
        return (
            StatusCode::UNAUTHORIZED,
            Json(json!({"detail": "Tên đăng nhập hoặc mật khẩu không chính xác."})),
        );
    };

    let stored_pwd = record.get("password").and_then(|v| v.as_str()).unwrap_or("");
    if !verify_password(stored_pwd, &req.password) {
        return (
            StatusCode::UNAUTHORIZED,
            Json(json!({"detail": "Tên đăng nhập hoặc mật khẩu không chính xác."})),
        );
    }

    let user_id = record
        .get("id")
        .and_then(|v| v.as_str())
        .unwrap_or("user_internal");
    let (token, _) =
        issue_token_with_expiry(&state.session_secret, user_id, &username, DEFAULT_TTL_SECONDS);
    let profile = build_profile(&record, &username);

    let resp = AuthResponse {
        status: "success".into(),
        message: format!("Đăng nhập thành công! Chào mừng {}.", profile.display_name),
        token,
        user: profile,
    };
    (StatusCode::OK, Json(serde_json::to_value(resp).unwrap_or_default()))
}

pub async fn refresh_token(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> impl IntoResponse {
    let auth_header = headers
        .get("authorization")
        .or_else(|| headers.get("x-auth-token"))
        .and_then(|v| v.to_str().ok());
    let Some(payload) = state.verify_bearer(auth_header, REFRESH_GRACE_SECONDS) else {
        return (
            StatusCode::UNAUTHORIZED,
            Json(json!({"detail": "Phiên đăng nhập không hợp lệ hoặc đã hết hạn."})),
        );
    };

    let users = state.load_users();
    let Some((username, record)) = find_user_for_payload(&users, &payload.sub, &payload.usr) else {
        return (
            StatusCode::UNAUTHORIZED,
            Json(json!({"detail": "Tài khoản không còn tồn tại."})),
        );
    };

    let (token, expires_at) = issue_token_with_expiry(
        &state.session_secret,
        &payload.sub,
        &username,
        DEFAULT_TTL_SECONDS,
    );
    let profile = build_profile(&record, &username);
    (
        StatusCode::OK,
        Json(json!({
            "token": token,
            "expires_at": expires_at,
            "user": profile,
        })),
    )
}

pub async fn get_me(State(state): State<AppState>, headers: HeaderMap) -> impl IntoResponse {
    let auth_header = headers
        .get("authorization")
        .or_else(|| headers.get("x-auth-token"))
        .and_then(|v| v.to_str().ok());
    let Some(payload) = state.verify_bearer(auth_header, 0) else {
        return (
            StatusCode::UNAUTHORIZED,
            Json(json!({"detail": "Yêu cầu đăng nhập."})),
        );
    };

    let users = state.load_users();
    let Some((username, record)) = find_user_for_payload(&users, &payload.sub, &payload.usr) else {
        return (
            StatusCode::NOT_FOUND,
            Json(json!({"detail": "Tài khoản không còn tồn tại."})),
        );
    };

    (
        StatusCode::OK,
        Json(serde_json::to_value(build_profile(&record, &username)).unwrap_or_default()),
    )
}

pub async fn change_password(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(req): Json<ChangePasswordRequest>,
) -> impl IntoResponse {
    if req.new_password.len() < 8 {
        return (
            StatusCode::BAD_REQUEST,
            Json(json!({"detail": "Mật khẩu mới phải có ít nhất 8 ký tự."})),
        );
    }
    let auth_header = headers
        .get("authorization")
        .or_else(|| headers.get("x-auth-token"))
        .and_then(|v| v.to_str().ok());
    let Some(payload) = state.verify_bearer(auth_header, 0) else {
        return (
            StatusCode::UNAUTHORIZED,
            Json(json!({"detail": "Yêu cầu đăng nhập."})),
        );
    };

    let mut users = state.load_users();
    let Some((key, mut record)) = find_user_for_payload(&users, &payload.sub, &payload.usr) else {
        return (
            StatusCode::UNAUTHORIZED,
            Json(json!({"detail": "Tài khoản không còn tồn tại."})),
        );
    };
    let stored = record.get("password").and_then(|v| v.as_str()).unwrap_or("");
    if !verify_password(stored, &req.old_password) {
        return (
            StatusCode::BAD_REQUEST,
            Json(json!({"detail": "Mật khẩu hiện tại không chính xác."})),
        );
    }
    if let Some(obj) = record.as_object_mut() {
        obj.insert("password".into(), Value::String(hash_password(&req.new_password)));
    }
    users.insert(key, record);
    let _ = state.save_users(&users);
    (StatusCode::OK, Json(json!({"status": "ok"})))
}

fn find_user_for_payload(
    users: &std::collections::HashMap<String, Value>,
    sub: &str,
    usr: &str,
) -> Option<(String, Value)> {
    if !usr.is_empty() {
        if let Some(rec) = users.get(usr) {
            if rec.get("id").and_then(|v| v.as_str()).unwrap_or("user_internal") == sub {
                return Some((usr.to_string(), rec.clone()));
            }
        }
    }
    users.iter().find_map(|(k, v)| {
        if v.get("id").and_then(|id| id.as_str()) == Some(sub) {
            Some((k.clone(), v.clone()))
        } else {
            None
        }
    })
}

fn build_profile(record: &Value, identifier: &str) -> UserProfile {
    UserProfile {
        id: record
            .get("id")
            .and_then(|v| v.as_str())
            .unwrap_or("user_internal")
            .to_string(),
        email: record
            .get("email")
            .and_then(|v| v.as_str())
            .unwrap_or(&format!("{}@truyencloud.vn", identifier))
            .to_string(),
        display_name: record
            .get("display_name")
            .and_then(|v| v.as_str())
            .unwrap_or(identifier)
            .to_string(),
        bio: record
            .get("bio")
            .and_then(|v| v.as_str())
            .unwrap_or("Thành viên TruyenCloud Rust Edition")
            .to_string(),
        vip_tier: record
            .get("vip_tier")
            .and_then(|v| v.as_str())
            .unwrap_or("✦ VIP Tinh Anh 2026")
            .to_string(),
        avatar_url: record
            .get("avatar_url")
            .and_then(|v| v.as_str())
            .unwrap_or("/static/user_avatar.jpg")
            .to_string(),
        banner_url: record
            .get("banner_url")
            .and_then(|v| v.as_str())
            .unwrap_or("/static/user_banner.jpg")
            .to_string(),
        auth_provider: "email".into(),
        is_logged_in: true,
        is_admin: record.get("is_admin").and_then(|v| v.as_bool()) == Some(true),
    }
}
