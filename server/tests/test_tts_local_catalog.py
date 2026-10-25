"""Danh mục giọng thật THEO MÔ HÌNH (Nano mặc định, Turbo tuỳ chọn) và cách mười id hồ sơ cũ được đọc."""

import importlib.util
import json
import re
import unicodedata
from pathlib import Path

import pytest

from services.tts_catalog_data import MODE_NANO, MODE_TURBO
from services.tts_local_catalog import (
    LEGACY_PROFILES,
    active_mode,
    by_id,
    by_preset,
    catalog,
    default_ids,
    legacy_map,
)
from services.tts_voice_profiles import PROFILE_VOICE_CONFIG, catalog_voices, identify_voice

LEGACY_IDS = {"mai_phuong", "thao_vy", "thu_ha", "cam_tu", "bao_anh", "google_std",
              "minh_quang", "dinh_trong", "bao_long", "hoang_nam"}
MALE_LEGACY = {"minh_quang", "dinh_trong", "bao_long", "hoang_nam"}
NANO_IDS = ["adam", "ai_han", "my_duyen", "duc_tri", "huu_quan", "xuan_tien", "mai_anh", "truc_ly", "anh_khoi",
            "minh_quan", "manh_dung"]


def _slug(name: str) -> str:
    text = unicodedata.normalize("NFD", name.lower().replace("đ", "d"))
    text = "".join(c for c in text if not unicodedata.combining(c))
    return re.sub(r"[^a-z0-9]+", "_", text).strip("_")


@pytest.fixture
def turbo(monkeypatch):
    monkeypatch.setenv("TTS_LOCAL_MODEL", "v3turbo")


def test_mac_dinh_la_nano_va_gia_tri_la_cung_ve_nano(monkeypatch):
    assert active_mode() == MODE_NANO
    for raw, mode in (("v3turbo", MODE_TURBO), ("TURBO", MODE_TURBO), (" v3nano ", MODE_NANO), ("nano", MODE_NANO),
                      ("gpu", MODE_NANO), ("", MODE_NANO)):
        monkeypatch.setenv("TTS_LOCAL_MODEL", raw)
        assert active_mode() == mode, raw


def test_nano_co_dung_11_giong_dung_thu_tu_cua_goi():
    assert [e["id"] for e in catalog()] == NANO_IDS
    assert len(by_id()) == len(by_preset()) == 11


def test_turbo_co_du_25_giong(turbo):
    assert len(catalog()) == 25 and len(by_id()) == len(by_preset()) == 25
    assert "thien_minh" in by_id() and "ai_han" not in by_id()


@pytest.mark.parametrize("mode", [MODE_NANO, MODE_TURBO])
def test_id_la_slug_ascii_va_khong_trung_id_ho_so_cu(mode):
    for entry in catalog(mode):
        assert entry["id"] == _slug(entry["preset_name"]) and re.fullmatch(r"[a-z0-9_]+", entry["id"])
        assert entry["model"] == mode
    assert not LEGACY_IDS & set(by_id(mode))


@pytest.mark.parametrize("mode", [MODE_NANO, MODE_TURBO])
def test_moi_giong_co_gioi_tinh_vung_mien_va_phong_cach_that(mode):
    for entry in catalog(mode):
        assert entry["gender"] in ("Nữ", "Nam")
        assert entry["region"] in ("Bắc", "Trung", "Nam")
        assert entry["style"] in ("tin tức", "tự nhiên", "kể chuyện", "đọc truyện")
        assert entry["region"] in entry["description"] and "thật" in entry["description"]


def test_thong_tin_giong_nano_dung_nhu_goi_liet_ke():
    nano = by_id(MODE_NANO)
    assert (nano["my_duyen"]["gender"], nano["my_duyen"]["region"], nano["my_duyen"]["style"]) == ("Nữ", "Bắc", "đọc truyện")
    assert (nano["duc_tri"]["gender"], nano["duc_tri"]["region"], nano["duc_tri"]["style"]) == ("Nam", "Bắc", "đọc truyện")
    assert (nano["adam"]["region"], nano["ai_han"]["region"]) == ("Nam", "Nam")
    assert nano["anh_khoi"]["style"] == "kể chuyện"
    assert nano["my_duyen"]["label"] == "Mỹ Duyên — Nữ · Bắc · Phong cách đọc truyện"
    assert by_id(MODE_TURBO)["mai_anh"]["label"] == "⭐ Mai Anh — Nữ · Bắc · Phong cách tin tức"
    assert by_id(MODE_TURBO)["my_duyen"]["region"] == "Nam", "cùng tên nhưng là bản ghi khác ở Turbo"


def test_giong_mac_dinh_theo_mo_hinh(turbo, monkeypatch):
    assert default_ids(MODE_NANO) == ("my_duyen", "duc_tri")
    assert default_ids() == ("mai_anh", "thien_minh")
    monkeypatch.delenv("TTS_LOCAL_MODEL")
    assert default_ids() == ("my_duyen", "duc_tri")
    assert [e["id"] for e in catalog() if e["default_for"]] == ["my_duyen", "duc_tri"]


@pytest.mark.parametrize("mode", [MODE_NANO, MODE_TURBO])
def test_ho_so_cu_doc_bang_giong_mac_dinh_theo_gioi_tinh_cua_mo_hinh(mode, monkeypatch):
    monkeypatch.setenv("TTS_LOCAL_MODEL", mode)
    female, male = default_ids(mode)
    assert set(LEGACY_PROFILES) == LEGACY_IDS == set(PROFILE_VOICE_CONFIG)
    assert legacy_map() == {pid: (male if pid in MALE_LEGACY else female) for pid in LEGACY_IDS}
    for pid, cfg in PROFILE_VOICE_CONFIG.items():
        assert cfg["voice_id"] == legacy_map()[pid] and cfg["voice"] == by_id()[cfg["voice_id"]]["preset_name"]
        assert identify_voice(pid).profile_id == pid and identify_voice(pid).voice_id == cfg["voice_id"]


def test_profile_voice_config_doi_theo_mo_hinh_dang_chay(monkeypatch):
    assert PROFILE_VOICE_CONFIG["mai_phuong"]["voice"] == "Mỹ Duyên"
    assert PROFILE_VOICE_CONFIG["minh_quang"]["voice"] == "Đức Trí"
    monkeypatch.setenv("TTS_LOCAL_MODEL", "v3turbo")
    assert PROFILE_VOICE_CONFIG["mai_phuong"]["voice"] == "Mai Anh"
    assert dict(PROFILE_VOICE_CONFIG)["hoang_nam"]["voice"] == "Thiện Minh"


def test_catalog_voices_cho_app_co_du_khoa_va_legacy_ids():
    voices = catalog_voices()
    assert [v["id"] for v in voices] == NANO_IDS
    for v in voices:
        for key in ("id", "name", "gender", "region", "style", "description", "preset_name", "legacy_ids", "model",
                    "default_for", "label"):
            assert key in v
    by = {v["id"]: v for v in voices}
    assert set(by["my_duyen"]["legacy_ids"]) == LEGACY_IDS - MALE_LEGACY
    assert set(by["duc_tri"]["legacy_ids"]) == MALE_LEGACY
    assert by["mai_anh"]["legacy_ids"] == []


@pytest.mark.parametrize("mode,asset", [(MODE_NANO, "voices_v3_nano.json"), (MODE_TURBO, "voices_v3_turbo.json")])
def test_khop_voi_danh_sach_giong_trong_goi_vieneu_neu_da_cai(mode, asset):
    spec = importlib.util.find_spec("vieneu")
    if spec is None or not spec.submodule_search_locations:
        pytest.skip("gói vieneu không cài trong môi trường test (đường chạy thật không cần cho bộ test)")
    path = Path(next(iter(spec.submodule_search_locations))) / "assets" / asset
    if not path.is_file():
        pytest.skip(f"gói vieneu không có {asset}")
    presets = json.loads(path.read_text(encoding="utf-8"))["presets"]
    assert set(presets) == set(by_preset(mode))
    for name, meta in presets.items():
        entry = by_preset(mode)[name]
        assert (entry["gender"] == "Nam") == (meta["gender"] == "male"), name
        assert meta["description"] == f"{entry['gender']} · {entry['region']} · {entry['style_label']}", name
