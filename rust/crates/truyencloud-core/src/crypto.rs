use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use hmac::{Hmac, Mac};
use pbkdf2::pbkdf2_hmac;
use serde::{Deserialize, Serialize};
use sha2::Sha256;
use std::time::{SystemTime, UNIX_EPOCH};
use uuid::Uuid;

type HmacSha256 = Hmac<Sha256>;

pub const DEFAULT_TTL_SECONDS: i64 = 30 * 24 * 3600;
pub const REFRESH_GRACE_SECONDS: i64 = 180 * 24 * 3600;
const PBKDF2_ITERATIONS: u32 = 100_000;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct TokenPayload {
    pub sub: String,
    #[serde(default)]
    pub usr: String,
    pub iat: i64,
    pub exp: i64,
}

pub fn now_epoch_secs() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

/// Tạo mã băm mật khẩu theo đúng định dạng `pbkdf2:sha256:100000$<salt_hex>$<key_hex>` của bản Python.
pub fn hash_password(password: &str) -> String {
    let salt_hex = Uuid::new_v4().simple().to_string();
    let mut key = [0u8; 32];
    pbkdf2_hmac::<Sha256>(
        password.as_bytes(),
        salt_hex.as_bytes(),
        PBKDF2_ITERATIONS,
        &mut key,
    );
    format!("pbkdf2:sha256:{}${}${}", PBKDF2_ITERATIONS, salt_hex, hex::encode(key))
}

/// Xác minh mật khẩu theo định dạng `pbkdf2:sha256:100000$<salt_hex>$<key_hex>` (so sánh hằng định).
pub fn verify_password(stored_hash: &str, provided_password: &str) -> bool {
    if stored_hash.is_empty() || provided_password.is_empty() {
        return false;
    }
    if !stored_hash.starts_with("pbkdf2:sha256:") {
        return false;
    }
    let parts: Vec<&str> = stored_hash.split('$').collect();
    if parts.len() != 3 {
        return false;
    }
    let iter_str = parts[0].trim_start_matches("pbkdf2:sha256:");
    let iterations: u32 = iter_str.parse().unwrap_or(PBKDF2_ITERATIONS);
    let salt = parts[1];
    let expected_hex = parts[2];

    let mut derived = [0u8; 32];
    pbkdf2_hmac::<Sha256>(
        provided_password.as_bytes(),
        salt.as_bytes(),
        iterations,
        &mut derived,
    );
    let computed_hex = hex::encode(derived);
    constant_time_eq(expected_hex.as_bytes(), computed_hex.as_bytes())
}

/// Cấp token ký HMAC-SHA256 tương thích 100% với `services/auth_token.py`.
pub fn issue_token_with_expiry(
    secret: &str,
    user_id: &str,
    username: &str,
    ttl_seconds: i64,
) -> (String, i64) {
    let now = now_epoch_secs();
    let exp = now + ttl_seconds;
    let payload = TokenPayload {
        sub: user_id.to_string(),
        usr: username.to_string(),
        iat: now,
        exp,
    };
    let json_bytes = serde_json::to_vec(&payload).unwrap_or_default();
    let body = URL_SAFE_NO_PAD.encode(&json_bytes);
    let sig = sign_hmac(secret.as_bytes(), body.as_bytes());
    let sig_b64 = URL_SAFE_NO_PAD.encode(sig);
    (format!("{}.{}", body, sig_b64), exp)
}

/// Xác minh chữ ký HMAC-SHA256 và hạn dùng của token.
pub fn verify_token(secret: &str, token: &str, grace_seconds: i64) -> Option<TokenPayload> {
    let (body, sig_part) = token.rsplit_once('.')?;
    let provided_sig = URL_SAFE_NO_PAD.decode(sig_part).ok()?;
    let expected_sig = sign_hmac(secret.as_bytes(), body.as_bytes());
    if !constant_time_eq(&provided_sig, &expected_sig) {
        return None;
    }
    let decoded_body = URL_SAFE_NO_PAD.decode(body).ok()?;
    let payload: TokenPayload = serde_json::from_slice(&decoded_body).ok()?;
    if payload.sub.trim().is_empty() {
        return None;
    }
    let now = now_epoch_secs();
    if payload.exp + grace_seconds.max(0) < now {
        return None;
    }
    Some(payload)
}

fn sign_hmac(secret: &[u8], message: &[u8]) -> Vec<u8> {
    let mut mac = HmacSha256::new_from_slice(secret).expect("HMAC accepts any key length");
    mac.update(message);
    mac.finalize().into_bytes().to_vec()
}

fn constant_time_eq(a: &[u8], b: &[u8]) -> bool {
    if a.len() != b.len() {
        return false;
    }
    let mut diff = 0u8;
    for (&x, &y) in a.iter().zip(b.iter()) {
        diff |= x ^ y;
    }
    diff == 0
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn password_hash_and_verify_round_trip() {
        let h = hash_password("MatKhauBiMat2026!");
        assert!(h.starts_with("pbkdf2:sha256:100000$"));
        assert!(verify_password(&h, "MatKhauBiMat2026!"));
        assert!(!verify_password(&h, "SaiMatKhau"));
    }

    #[test]
    fn token_issue_and_verify_round_trip() {
        let secret = "truyencloud_super_secret_key_for_testing_2026";
        let (tok, exp) = issue_token_with_expiry(secret, "user_test1", "test1", 3600);
        assert!(exp > now_epoch_secs());
        let verified = verify_token(secret, &tok, 0).expect("valid token");
        assert_eq!(verified.sub, "user_test1");
        assert_eq!(verified.usr, "test1");
        assert!(verify_token("wrong_secret", &tok, 0).is_none());
    }
}
