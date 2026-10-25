import ipaddress
import logging
import os
from functools import lru_cache
from dotenv import load_dotenv

load_dotenv()

logger = logging.getLogger("truyencloud.config")


@lru_cache(maxsize=8)
def _parse_networks(raw: str) -> tuple:
    networks = []
    for value in raw.split(","):
        value = value.strip()
        if not value:
            continue
        try:
            networks.append(ipaddress.ip_network(value, strict=False))
        except ValueError:
            logger.warning("Bỏ qua giá trị TRUSTED_PROXY_IPS không hợp lệ: %r", value)
    return tuple(networks)


class Settings:
    """Application configuration settings."""
    HOST: str = os.getenv("HOST", "0.0.0.0")
    PORT: int = int(os.getenv("PORT", "8000"))
    DEBUG: bool = os.getenv("DEBUG", "true").lower() in ("true", "1", "yes")

    # Base URL for static assets and self-referencing links
    BASE_URL: str = os.getenv("BASE_URL", f"http://127.0.0.1:{os.getenv('PORT', '8000')}")

    # CORS origins: comma-separated list, or "*" for development.
    # Khi DEBUG=false, "*" hoặc rỗng nghĩa là KHÔNG cho origin trình duyệt nào —
    # app di động gọi thẳng API nên không cần CORS. Web client thì liệt kê origin cụ thể.
    CORS_ORIGINS: str = os.getenv("CORS_ORIGINS", "*" if os.getenv("DEBUG", "true").lower() in ("true", "1", "yes") else "")

    # Proxy được phép khai IP thật qua CF-Connecting-IP / X-Forwarded-For (IP hoặc CIDR,
    # cách nhau dấu phẩy). Mặc định chỉ tin loopback: cloudflared chạy cùng máy và cổng
    # 8000 chỉ bind 127.0.0.1. Chạy trong Docker thì thêm dải bridge (172.16.0.0/12) —
    # docker-compose.yml đã đặt sẵn. Đặt rỗng để không tin proxy nào.
    TRUSTED_PROXY_IPS: str = os.getenv("TRUSTED_PROXY_IPS", "127.0.0.1,::1")

    # Supabase credentials
    SUPABASE_URL: str = os.getenv("SUPABASE_URL", "")
    SUPABASE_SERVICE_KEY: str = os.getenv("SUPABASE_SERVICE_KEY", "")


    # Request timeouts
    SCRAPER_TIMEOUT_SECONDS: float = 30.0
    TRANSLATE_TIMEOUT_SECONDS: float = 120.0

    # Mật khẩu tài khoản thử test1 (chỉ tạo khi DEBUG). Không dùng làm bí mật nào khác.
    DEFAULT_TEST_PASSWORD: str = os.getenv("DEFAULT_TEST_PASSWORD", "changeme_on_first_login")

    @property
    def cors_origins_list(self) -> list:
        """Parse CORS_ORIGINS into a list of strings."""
        raw = self.CORS_ORIGINS.strip()
        if not raw:
            return []
        if raw == "*":
            return ["*"]
        return [origin.strip() for origin in raw.split(",") if origin.strip()]

    @property
    def effective_cors_origins(self) -> list:
        """Origins thực sự cấp cho CORSMiddleware: production không bao giờ mở '*'."""
        origins = self.cors_origins_list
        if self.DEBUG:
            return origins or ["*"]
        return [origin for origin in origins if origin != "*"]

    @property
    def trusted_proxy_networks(self) -> tuple:
        return _parse_networks(self.TRUSTED_PROXY_IPS)

settings = Settings()
