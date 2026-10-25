#!/bin/bash
set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CLOUDFLARED="$HOME/.local/bin/cloudflared"

if [ ! -f "$CLOUDFLARED" ]; then
    echo "cloudflared chưa được cài đặt."
    exit 1
fi

if [ -n "$1" ]; then
    echo "Khởi động Cloudflare Tunnel với Token..."
    exec "$CLOUDFLARED" tunnel run --token "$1"
elif [ -f "$HOME/.cloudflared/config.yml" ]; then
    echo "Khởi động Cloudflare Tunnel từ cấu hình config.yml..."
    exec "$CLOUDFLARED" tunnel run truyencloud
else
    echo "Sử dụng: $0 <CLOUDFLARE_TUNNEL_TOKEN> hoặc cấu hình qua 'cloudflared tunnel login'"
    exit 1
fi
