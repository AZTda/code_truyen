"""Nhận ra giọng người dùng đã chọn và công bố danh mục giọng ở /v1/tts/voices.

Giọng đọc thật do VieNeu-TTS chạy trên máy chủ (danh mục theo mô hình đang chạy, tts_local_catalog.py). Máy người dùng
có thể gửi:
  - id giọng (`my_duyen`, `duc_tri`...) hoặc tên giọng (`Mỹ Duyên`, có/không dấu, hoa/thường);
  - MỘT TRONG MƯỜI id hồ sơ cũ (`mai_phuong`, `minh_quang`...) đã lưu và đồng bộ ở máy: giữ nguyên id, đọc bằng giọng
    mặc định theo giới tính của mô hình đang chạy;
  - id của mô hình khác (máy chủ đổi Turbo <-> Nano): giọng mặc định cùng giới tính;
  - chuỗi lạ: đoán giới tính từ từ khoá, không đoán được thì giọng nữ mặc định (như trước nay).
Không có tham số cao độ/nhịp: VieNeu không có điều khiển đó, tốc độ do trình phát của app áp.
"""

from collections.abc import Mapping
from dataclasses import dataclass
from typing import Any, Dict, Iterator, List, Optional

from services.tts_local_catalog import (
    LEGACY_PROFILES,
    active_mode,
    by_id,
    by_preset,
    catalog,
    default_ids,
    default_voice_id,
    legacy_map,
    legacy_target,
    other_mode_gender,
    slug,
)

DEFAULT_PROFILE_ID = "mai_phuong"


class _LegacyProfiles(Mapping):
    """Mười hồ sơ cũ dạng dict (khoá cũ giữ nguyên); giọng đứng sau tính theo mô hình đang chạy mỗi lần đọc."""

    def __getitem__(self, profile_id: str) -> Dict[str, Any]:
        name, gender = LEGACY_PROFILES[profile_id]
        target = by_id()[legacy_target(profile_id)]
        return {
            "voice": target["preset_name"],
            "voice_id": target["id"],
            "gender": gender,
            "name": name,
            "description": f"Hồ sơ cũ «{name}», nay đọc bằng giọng thật {target['name']} ({target['description'].lower()})",
        }

    def __iter__(self) -> Iterator[str]:
        return iter(LEGACY_PROFILES)

    def __len__(self) -> int:
        return len(LEGACY_PROFILES)


PROFILE_VOICE_CONFIG: Mapping = _LegacyProfiles()

# Chỉ để nhận ra id rác app cũ từng lưu (tên thô của giọng máy chủ đời trước, "male", "nu"...): gợi ý giới tính, không
# còn là đường đọc nào cả.
_MALE_TOKENS = frozenset({"nam", "male", "man", "boy", "guy", "yunxi", "namminh", "guyneural", "yunxineural", "namminhneural"})
_FEMALE_TOKENS = frozenset({"nu", "female", "woman", "girl", "jenny", "xiaoxiao", "hoaimy", "jennyneural", "xiaoxiaoneural", "hoaimyneural"})


@dataclass(frozen=True)
class VoiceIdentity:
    """Giọng thật được chọn: id/tên giọng VieNeu, giới tính, mô hình, và id hồ sơ cũ nếu chọn bằng hồ sơ cũ."""

    voice_id: str
    preset_name: str
    is_male: bool
    profile_id: Optional[str] = None
    mode: str = ""


def _of_voice(voice_id: str, mode: str, profile_id: Optional[str] = None) -> VoiceIdentity:
    entry = by_id(mode)[voice_id]
    return VoiceIdentity(voice_id, entry["preset_name"], entry["gender"] == "Nam", profile_id, mode)


def _gender_hint(key: str) -> Optional[bool]:
    """True = nam, False = nữ, None = không có dấu hiệu rõ (hoặc mâu thuẫn). Xét từng TỪ: 'vietnamese' không phải 'nam'."""
    tokens = {t.rstrip("0123456789") for t in key.split("_")}
    compact = key.replace("_", "")
    male = bool(tokens & _MALE_TOKENS) or "namminh" in compact
    female = bool(tokens & _FEMALE_TOKENS) or "hoaimy" in compact
    if male == female:
        return None
    return male


def _longest_prefix(key: str, ids) -> Optional[str]:
    for voice_id in sorted(ids, key=len, reverse=True):
        if key.startswith(voice_id + "_"):
            return voice_id
    return None


def identify_voice(voice: Any) -> VoiceIdentity:
    """Nhận ra giọng từ id, tên giọng, id hồ sơ cũ, id của mô hình khác hoặc chuỗi lạ (xem docstring module)."""
    mode = active_mode()
    voices = by_id(mode)
    key = slug(voice)
    if not key:
        return _of_voice(legacy_target(DEFAULT_PROFILE_ID, mode), mode, DEFAULT_PROFILE_ID)
    if key in voices:
        return _of_voice(key, mode)
    if key in LEGACY_PROFILES:
        return _of_voice(legacy_target(key, mode), mode, key)
    for pid in LEGACY_PROFILES:
        if key.startswith(pid + "_"):  # 'minh_quang_bac_tram_am' (tên hiển thị cũ của máy chủ)
            return _of_voice(legacy_target(pid, mode), mode, pid)
    foreign = other_mode_gender(key, mode)  # trước tiền tố: 'adam_bua' (Turbo) không phải 'adam' (Nano) + đuôi
    if foreign is not None:
        return _of_voice(default_voice_id(foreign, mode), mode)
    prefixed = _longest_prefix(key, voices)
    if prefixed:
        return _of_voice(prefixed, mode)
    male = _gender_hint(key)
    return _of_voice(default_voice_id(bool(male), mode), mode)


def preset_exists(preset_name: str) -> bool:
    return preset_name in by_preset()


def public_voice_catalog() -> List[Dict[str, Any]]:
    """Mười hồ sơ cũ ở /v1/tts/voices, GIỮ NGUYÊN các khoá cũ; `voice_model` nay là tên giọng thật đứng sau hồ sơ."""
    voices = []
    for pid, cfg in PROFILE_VOICE_CONFIG.items():
        voices.append({
            "id": pid,
            "name": cfg["name"],
            "voice_model": cfg["voice"],
            "voice_id": cfg["voice_id"],
            "gender": cfg["gender"],
            "pitch_offset": "+0Hz",
            "rate_offset": "+0%",
            "description": cfg["description"],
            "voice_models": {"vi": cfg["voice"], "en": cfg["voice"], "zh": "device"},
        })
    return voices


_CATALOG_KEYS = ("id", "name", "gender", "region", "style", "style_label", "description", "preset_name", "featured",
                 "default_for", "model", "label")


def catalog_voices() -> List[Dict[str, Any]]:
    """Giọng thật của mô hình đang chạy: id, name, gender (Nữ/Nam), region (Bắc/Trung/Nam), style, ... legacy_ids."""
    mapping = legacy_map()
    voices = []
    for entry in catalog():
        item = {k: entry[k] for k in _CATALOG_KEYS}
        item["legacy_ids"] = [pid for pid, vid in mapping.items() if vid == entry["id"]]
        voices.append(item)
    return voices


def default_voices() -> List[Dict[str, str]]:
    """Giọng mặc định theo giới tính (khi id lạ): dạng cũ của `base_voices`."""
    female, male = (by_id()[vid] for vid in default_ids())
    return [
        {"voice_model": female["preset_name"], "gender": "Nữ", "label": female["name"]},
        {"voice_model": male["preset_name"], "gender": "Nam", "label": male["name"]},
    ]


def voices_note() -> str:
    return (
        f"{len(catalog())} giọng thật do máy chủ tự chạy (mã nguồn mở VieNeu-TTS, mô hình {active_mode()}). Hồ sơ cũ "
        "vẫn dùng được và đọc bằng giọng mặc định cùng giới tính. Đoạn tiếng Trung do giọng máy của điện thoại đọc."
    )
