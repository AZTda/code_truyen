#!/bin/bash
# Start TruyenCloud FastAPI backend server locally on macOS.
#
# Mặc định chỉ lắng nghe 127.0.0.1 (cloudflared chạy cùng máy nên không cần mở cổng ra LAN).
# Muốn thử bằng điện thoại trong mạng nội bộ:  BIND_HOST=0.0.0.0 ./run_server.sh
DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
cd "$DIR"

if [ ! -d ".venv" ]; then
    echo "Virtual environment not found. Creating..."
    /Users/diemtran/.local/bin/uv venv --python 3.13 .venv
    /Users/diemtran/.local/bin/uv pip install --python .venv/bin/python -r requirements.txt
fi

# Đọc một giá trị không bí mật từ .env (không source cả tệp để khỏi export khoá bí mật)
env_value() { grep -E "^$1=" .env 2>/dev/null | head -1 | cut -d= -f2- | tr -d "\"'" ; }

BIND_HOST="${BIND_HOST:-127.0.0.1}"
PORT="${PORT:-$(env_value PORT)}"
PORT="${PORT:-8000}"
DEBUG_VALUE="$(echo "${DEBUG:-$(env_value DEBUG)}" | tr '[:upper:]' '[:lower:]')"

RELOAD_FLAG=""
case "$DEBUG_VALUE" in
    true|1|yes) RELOAD_FLAG="--reload" ;;
esac

echo "Starting TruyenCloud API server on ${BIND_HOST}:${PORT} ${RELOAD_FLAG}"
exec ./.venv/bin/uvicorn server:app --host "$BIND_HOST" --port "$PORT" $RELOAD_FLAG
