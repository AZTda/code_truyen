"""Danh mục giọng thật của VieNeu-TTS THEO MÔ HÌNH đang chạy, và cách mười id hồ sơ cũ được đọc.

Mô hình chọn bằng biến môi trường TTS_LOCAL_MODEL: `v3nano` (mặc định, 11 giọng) hoặc `v3turbo` (25 giọng).
`id` = tên giọng bỏ dấu (NFD, đ->d), chữ thường, khoảng trắng -> "_"; không id nào trùng mười id cũ.

Mười id hồ sơ cũ (mai_phuong...) được lưu và đồng bộ ở máy người dùng nên KHÔNG đổi id. Theo quyết định của chủ app,
hồ sơ cũ và id lạ đọc bằng giọng MẶC ĐỊNH của mô hình đang chạy theo giới tính (Nano: nữ Mỹ Duyên, nam Đức Trí).
Id của mô hình KIA (vd. 'thien_minh' của Turbo khi máy chủ chạy Nano) cũng đổi theo giới tính của giọng đó, không rơi
về giọng nữ.
"""

import os
import re
import unicodedata
from functools import lru_cache
from typing import Any, Dict, Optional, Tuple

from services.tts_catalog_data import DEFAULTS, MODE_NANO, MODE_TURBO, MODES, PRESETS

_ALIASES = {
    "v3nano": MODE_NANO, "nano": MODE_NANO, "v3_nano": MODE_NANO, "v3-nano": MODE_NANO,
    "v3turbo": MODE_TURBO, "turbo": MODE_TURBO, "v3_turbo": MODE_TURBO, "v3-turbo": MODE_TURBO,
}

# id hồ sơ cũ -> (tên hiển thị cũ, giới tính). Thứ tự giữ như trước: app cũ liệt kê theo đây.
LEGACY_PROFILES: Dict[str, Tuple[str, str]] = {
    "mai_phuong": ("Mai Phương", "Nữ"),
    "thao_vy": ("Thảo Vy", "Nữ"),
    "thu_ha": ("Thu Hà", "Nữ"),
    "cam_tu": ("Cẩm Tú", "Nữ"),
    "bao_anh": ("Bảo Anh", "Nữ"),
    "google_std": ("Mặc định", "Nữ"),
    "minh_quang": ("Minh Quang", "Nam"),
    "dinh_trong": ("Đình Trọng", "Nam"),
    "bao_long": ("Bảo Long", "Nam"),
    "hoang_nam": ("Hoàng Nam", "Nam"),
}


def active_mode() -> str:
    """Mô hình đang dùng; giá trị lạ -> Nano (mặc định an toàn: nhẹ nhất)."""
    raw = (os.getenv("TTS_LOCAL_MODEL") or "").strip().lower()
    return _ALIASES.get(raw, MODE_NANO)


def _mode(mode: Optional[str]) -> str:
    return mode if mode in MODES else active_mode()


def slug(name: Any) -> str:
    """'Đình Trọng ' -> 'dinh_trong'; 'Mỹ Duyên' -> 'my_duyen'."""
    text = unicodedata.normalize("NFD", str(name or "").lower().replace("đ", "d"))
    text = "".join(c for c in text if not unicodedata.combining(c))
    return re.sub(r"[^a-z0-9]+", "_", text).strip("_")


def _style(label: str) -> str:
    for prefix in ("Phong cách ", "Giọng đọc "):
        if label.startswith(prefix):
            return label[len(prefix):]
    return label.lower()


def _entry(mode: str, preset: str, gender: str, region: str, label: str, featured: Optional[int]) -> Dict[str, Any]:
    voice_id = slug(preset)
    female, male = DEFAULTS[mode]
    return {
        "id": voice_id,
        "name": preset,
        "preset_name": preset,
        "gender": gender,
        "region": region,
        "style": _style(label),
        "style_label": label,
        "featured": featured,
        "default_for": "female" if voice_id == female else ("male" if voice_id == male else None),
        "model": mode,
        "label": f"{'⭐ ' if featured else ''}{preset} — {gender} · {region} · {label}",
        "description": f"Giọng thật, miền {region} · {gender.lower()} · {label[0].lower() + label[1:]}",
    }


@lru_cache(maxsize=None)
def _built(mode: str) -> Tuple[Tuple[Dict[str, Any], ...], Dict[str, Dict[str, Any]], Dict[str, Dict[str, Any]]]:
    entries = tuple(_entry(mode, *row) for row in PRESETS[mode])
    return entries, {e["id"]: e for e in entries}, {e["preset_name"]: e for e in entries}


def catalog(mode: Optional[str] = None) -> Tuple[Dict[str, Any], ...]:
    return _built(_mode(mode))[0]


def by_id(mode: Optional[str] = None) -> Dict[str, Dict[str, Any]]:
    return _built(_mode(mode))[1]


def by_preset(mode: Optional[str] = None) -> Dict[str, Dict[str, Any]]:
    return _built(_mode(mode))[2]


def default_ids(mode: Optional[str] = None) -> Tuple[str, str]:
    """(id nữ mặc định, id nam mặc định) của mô hình."""
    return DEFAULTS[_mode(mode)]


def default_voice_id(is_male: bool, mode: Optional[str] = None) -> str:
    female, male = default_ids(mode)
    return male if is_male else female


def other_mode_gender(voice_id: str, mode: Optional[str] = None) -> Optional[bool]:
    """Id thuộc danh mục mô hình KHÁC: True = nam, False = nữ; None nếu không thuộc mô hình nào khác."""
    current = _mode(mode)
    for other in MODES:
        if other != current and voice_id in by_id(other):
            return by_id(other)[voice_id]["gender"] == "Nam"
    return None


def legacy_target(profile_id: str, mode: Optional[str] = None) -> str:
    """Giọng thật đứng sau một id hồ sơ cũ: giọng mặc định theo giới tính của mô hình đang chạy."""
    return default_voice_id(LEGACY_PROFILES[profile_id][1] == "Nam", mode)


def legacy_map(mode: Optional[str] = None) -> Dict[str, str]:
    return {pid: legacy_target(pid, mode) for pid in LEGACY_PROFILES}
