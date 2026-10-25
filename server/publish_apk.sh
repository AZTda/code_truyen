#!/usr/bin/env bash
# ==============================================================================
# TruyenCloud - Kịch bản phát hành bản cập nhật APK Online (OTA)
# Sử dụng:
#   ./publish_apk.sh [đường_dẫn_apk] [version_code] [version_name] "[changelog]"
# Ví dụ:
#   ./publish_apk.sh app-release.apk 2 "1.0.1" "Tối ưu tìm kiếm và sửa lỗi chương"
# Hoặc chạy không đối số để tự động tìm APK mới build:
#   ./publish_apk.sh
#
# An toàn khi lỗi: APK được chép sang tệp tạm, sha256 + app_version.json được dựng xong và kiểm
# lại TRƯỚC khi có tệp nào bị thay. Chỉ khi mọi thứ đã sẵn sàng mới đổi tên (mv) vào chỗ cũ, nên
# một lỗi giữa chừng không bao giờ để lại APK mới đi kèm sha256 của bản cũ (app từ chối cài).
# Mọi giá trị người dùng nhập (changelog, tên phiên bản...) đi qua biến môi trường, không bị
# chèn vào mã Python.
# ==============================================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
STATIC_DIR="$SCRIPT_DIR/static"
DATA_DIR="$SCRIPT_DIR/data"
VERSION_FILE="$DATA_DIR/app_version.json"
TARGET_APK="$STATIC_DIR/truyencloud-latest.apk"
DOWNLOAD_URL="${PUBLISH_DOWNLOAD_URL:-https://truyen.achatvn.com/static/truyencloud-latest.apk}"
CHECK_URL="${PUBLISH_CHECK_URL:-https://truyen.achatvn.com/v1/app/check-update}"

mkdir -p "$STATIC_DIR"
mkdir -p "$DATA_DIR"

APK_PATH="$1"
V_CODE="$2"
V_NAME="$3"
CHANGELOG="$4"

if [ "$1" = "--help" ] || [ "$1" = "-h" ]; then
    echo "Sử dụng: ./publish_apk.sh [đường_dẫn_apk] [version_code] [version_name] [changelog]"
    echo ""
    echo "Ví dụ:"
    echo "  ./publish_apk.sh app.apk 2 \"1.0.1\" \"- Tối ưu tìm kiếm và sửa lỗi chương\""
    echo "  ./publish_apk.sh  (tự động tìm APK mới build trong ../app)"
    exit 0
fi

# 1. Tìm file APK nếu người dùng không truyền vào
if [ -z "$APK_PATH" ]; then
    echo "🔍 Đang tự động tìm file APK mới nhất trong thư mục build..."
    FOUND_APK=$(find "$SCRIPT_DIR/../app" -name "*.apk" -type f -not -path "*/intermediates/*" -exec ls -t {} + 2>/dev/null | head -n 1 || true)
    if [ -n "$FOUND_APK" ] && [ -f "$FOUND_APK" ]; then
        APK_PATH="$FOUND_APK"
        echo "✅ Đã tìm thấy APK: $APK_PATH"
    else
        echo "❌ Không tìm thấy file APK nào tự động."
        echo "👉 Hướng dẫn sử dụng: ./publish_apk.sh <đường_dẫn_file_apk> [version_code] [version_name] [changelog]"
        exit 1
    fi
fi

if [ ! -f "$APK_PATH" ]; then
    echo "❌ File APK không tồn tại: $APK_PATH"
    exit 1
fi

# 2. Đọc phiên bản hiện tại từ app_version.json nếu có (đường dẫn truyền qua môi trường, không chèn vào mã)
CURRENT_CODE=1
if [ -f "$VERSION_FILE" ]; then
    CURRENT_CODE=$(VERSION_FILE="$VERSION_FILE" python3 -c "import json, os; print(json.load(open(os.environ['VERSION_FILE'])).get('version_code', 1))" 2>/dev/null || echo 1)
fi

# 3. Tự tăng versionCode nếu không chỉ định
if [ -z "$V_CODE" ]; then
    V_CODE=$((CURRENT_CODE + 1))
    echo "ℹ️ Tự động tăng version_code: $CURRENT_CODE -> $V_CODE"
fi

case "$V_CODE" in
    ''|*[!0-9]*)
        echo "❌ version_code phải là số nguyên không âm, nhận được: $V_CODE"
        exit 1
        ;;
esac

if [ -z "$V_NAME" ]; then
    V_NAME="1.0.$V_CODE"
fi

if [ -z "$CHANGELOG" ]; then
    CHANGELOG="- Cập nhật và tối ưu hiệu năng phiên bản v$V_NAME"
fi

RELEASE_DATE=$(date "+%Y-%m-%d")

# 4. Chuẩn bị mọi thứ ở tệp TẠM cùng thư mục đích (cùng hệ tệp nên mv là nguyên tử)
TMP_APK=""
TMP_JSON=""
BACKUP_APK=""
cleanup() {
    rm -f "$TMP_APK" "$TMP_JSON" "$BACKUP_APK"
}
trap cleanup EXIT

TMP_APK="$(mktemp "$STATIC_DIR/.truyencloud-latest.XXXXXX")"
TMP_JSON="$(mktemp "$DATA_DIR/.app_version.XXXXXX")"
chmod 644 "$TMP_APK" "$TMP_JSON"  # mktemp tạo tệp 0600; máy chủ có thể chạy bằng tài khoản khác

echo "📦 Đang chuẩn bị APK (chưa thay bản đang phục vụ): $APK_PATH"
cp "$APK_PATH" "$TMP_APK"
FILE_SIZE=$(wc -c < "$TMP_APK" | tr -d ' ')
FILE_SIZE_MB=$(FILE_SIZE="$FILE_SIZE" python3 -c "import os; print(f\"{int(os.environ['FILE_SIZE']) / (1024 * 1024):.2f}\")")
# App so sha256 này với file tải về trước khi cài; tính trên bản đã sao chép để khớp đúng file được phục vụ
if command -v shasum >/dev/null 2>&1; then
    APK_SHA256=$(shasum -a 256 "$TMP_APK" | awk '{print $1}')
else
    APK_SHA256=$(sha256sum "$TMP_APK" | awk '{print $1}')
fi
if ! echo "$APK_SHA256" | grep -Eq '^[0-9a-f]{64}$'; then
    echo "❌ Không tính được sha256 cho $APK_PATH"
    exit 1
fi

# 5. Dựng app_version.json mới vào tệp tạm. Heredoc có dấu nháy ('PYEOF') nên shell không thay thế gì
#    bên trong; json.dump lo việc thoát ký tự cho dấu nháy, gạch chéo ngược, xuống dòng trong changelog.
VERSION_FILE="$VERSION_FILE" TMP_JSON="$TMP_JSON" V_CODE="$V_CODE" V_NAME="$V_NAME" \
CHANGELOG="$CHANGELOG" DOWNLOAD_URL="$DOWNLOAD_URL" FILE_SIZE="$FILE_SIZE" \
RELEASE_DATE="$RELEASE_DATE" APK_SHA256="$APK_SHA256" \
python3 - <<'PYEOF'
import json
import os

env = os.environ
data = {}
try:
    with open(env["VERSION_FILE"], "r", encoding="utf-8") as f:
        loaded = json.load(f)
    if isinstance(loaded, dict):
        data = loaded
except (OSError, ValueError):
    pass

data["version_code"] = int(env["V_CODE"])
data["version_name"] = env["V_NAME"]
data["download_url"] = env["DOWNLOAD_URL"]
data["file_size_bytes"] = int(env["FILE_SIZE"])
data["changelog"] = env["CHANGELOG"]
data["release_date"] = env["RELEASE_DATE"]
data["is_mandatory"] = False
data["sha256"] = env["APK_SHA256"]

with open(env["TMP_JSON"], "w", encoding="utf-8") as f:
    json.dump(data, f, ensure_ascii=False, indent=2)
PYEOF

# Đọc lại bản vừa dựng: phải là JSON hợp lệ và khớp đúng mã phiên bản + sha256 sắp phát hành
TMP_JSON="$TMP_JSON" V_CODE="$V_CODE" APK_SHA256="$APK_SHA256" python3 - <<'PYEOF'
import json
import os

with open(os.environ["TMP_JSON"], "r", encoding="utf-8") as f:
    data = json.load(f)
assert data["version_code"] == int(os.environ["V_CODE"]), "version_code lệch"
assert data["sha256"] == os.environ["APK_SHA256"], "sha256 lệch"
PYEOF

# 6. Mọi thứ đã sẵn sàng -> mới đổi vào chỗ. APK trước, manifest sau (manifest là điểm "chốt":
#    client chỉ thấy bản mới sau khi nó được đặt xuống). Manifest lỗi thì trả APK cũ về.
if [ -f "$TARGET_APK" ]; then
    BACKUP_APK="$(mktemp "$STATIC_DIR/.truyencloud-prev.XXXXXX")"
    cp -p "$TARGET_APK" "$BACKUP_APK"
fi
mv -f "$TMP_APK" "$TARGET_APK"
if ! mv -f "$TMP_JSON" "$VERSION_FILE"; then
    if [ -n "$BACKUP_APK" ]; then
        mv -f "$BACKUP_APK" "$TARGET_APK"
    fi
    echo "❌ Không ghi được $VERSION_FILE; đã trả APK cũ về, không có gì thay đổi."
    exit 1
fi
echo "✅ Đã cập nhật metadata thành công tại: $VERSION_FILE"

echo ""
echo "=========================================================="
echo "🎉 ĐÃ PHÁT HÀNH BẢN CẬP NHẬT ONLINE THÀNH CÔNG!"
echo "   - Phiên bản: v$V_NAME (Build $V_CODE)"
echo "   - Dung lượng: ${FILE_SIZE_MB} MB"
echo "   - SHA-256: $APK_SHA256"
echo "   - Ngày phát hành: $RELEASE_DATE"
echo "   - Link tải trực tiếp: $DOWNLOAD_URL"
echo "   - Link kiểm tra API: $CHECK_URL"
echo "=========================================================="
