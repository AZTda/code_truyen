"""Bộ đệm MP3 TTS: khoá, ghi nguyên tử, sidecar thời lượng và dọn LRU theo dung lượng."""

import os
import threading
import time

import pytest

from services import tts_cache


@pytest.fixture
def cache_dir(tmp_path):
    path = tmp_path / "only_tts_cache"
    path.mkdir()
    return path


def _put(cache_dir, name, size=1000, age=0.0, duration_ms=1234):
    md5 = name.ljust(32, "0")[:32]
    path = tts_cache.write_audio(str(cache_dir), md5, b"x" * size, duration_ms)
    when = time.time() - age
    os.utime(path, (when, when))
    return md5, path


def test_khoa_co_tien_to_mo_hinh_va_khong_co_toc_do():
    key = tts_cache.cache_key("xin chào", "Mỹ Duyên", "v3nano")
    assert key == "v5_vieneu|v3nano|xin chào|Mỹ Duyên"
    assert tts_cache.md5_of(key) == tts_cache.md5_of(tts_cache.cache_key("xin chào", "Mỹ Duyên", "v3nano"))
    assert tts_cache.md5_of(key) != tts_cache.md5_of(tts_cache.cache_key("xin chào", "Đức Trí", "v3nano"))
    assert tts_cache.md5_of(key) != tts_cache.md5_of(tts_cache.cache_key("xin chào", "Mỹ Duyên", "v3turbo"))


def test_ghi_nguyen_tu_khong_de_lai_tep_tam_va_ghi_thoi_luong(cache_dir):
    md5, path = _put(cache_dir, "abc", 5000, duration_ms=4321)
    assert tts_cache.is_cached(path) and tts_cache.read_duration_ms(path) == 4321
    assert sorted(os.listdir(cache_dir)) == [f"{md5}.mp3", f"{md5}.ms"]
    assert not any(".tmp_" in n for n in os.listdir(cache_dir))


def test_thieu_sidecar_thi_uoc_luong_tu_kich_thuoc_cbr(cache_dir):
    md5, path = _put(cache_dir, "abc", 16000)
    os.remove(path[:-4] + ".ms")
    assert tts_cache.read_duration_ms(path) == 2000  # 16000 byte / 8000 byte mỗi giây


def test_hai_luong_cung_ghi_mot_khoa_khong_dam_len_nhau(cache_dir):
    errors = []

    def writer(payload):
        try:
            for _ in range(20):
                tts_cache.write_audio(str(cache_dir), "k" * 32, payload * 5000, 100)
        except Exception as exc:  # pragma: no cover
            errors.append(exc)

    threads = [threading.Thread(target=writer, args=(c,)) for c in (b"a", b"b")]
    [t.start() for t in threads]
    [t.join() for t in threads]
    assert not errors
    data = (cache_dir / ("k" * 32 + ".mp3")).read_bytes()
    assert data in (b"a" * 5000, b"b" * 5000), "tệp phải nguyên vẹn của MỘT trong hai lượt ghi"
    assert not any(".tmp_" in n for n in os.listdir(cache_dir))


def test_don_lru_xoa_tep_lau_khong_dung_nhat_truoc(cache_dir):
    for i in range(10):
        _put(cache_dir, f"f{i}", 1000, age=1000 - i * 10)  # f0 cũ nhất
    stats = tts_cache.sweep(str(cache_dir), max_bytes=5200)
    assert stats["removed"] >= 5
    left = sorted(n for n in os.listdir(cache_dir) if n.endswith(".mp3"))
    assert "f9".ljust(32, "0") + ".mp3" in left and "f0".ljust(32, "0") + ".mp3" not in left
    for name in os.listdir(cache_dir):  # sidecar đi cùng tệp
        if name.endswith(".ms"):
            assert name[:-3] + ".mp3" in left


def test_khong_xoa_tep_dung_trong_vong_mot_phut(cache_dir):
    for i in range(4):
        _put(cache_dir, f"new{i}", 10_000, age=5)
    stats = tts_cache.sweep(str(cache_dir), max_bytes=1000)
    assert stats["removed"] == 0
    assert len([n for n in os.listdir(cache_dir) if n.endswith(".mp3")]) == 4


def test_vua_den_tran_thi_don_xuong_90_phan_tram(cache_dir):
    for i in range(20):
        _put(cache_dir, f"f{i}", 1000, age=500 + i)
    total = tts_cache.usage(str(cache_dir))[1]
    tts_cache.sweep(str(cache_dir), max_bytes=total - 100)
    after = tts_cache.usage(str(cache_dir))[1]
    assert after <= int((total - 100) * 0.9) + 1100


def test_don_sidecar_mo_coi_va_tep_tam_cu(cache_dir):
    old = time.time() - 3600
    (cache_dir / ("o" * 32 + ".ms")).write_text("100")
    (cache_dir / ("t" * 32 + ".mp3.tmp_abc")).write_bytes(b"zz")
    (cache_dir / ("fresh" + ".mp3.tmp_new")).write_bytes(b"zz")
    for name in ("o" * 32 + ".ms", "t" * 32 + ".mp3.tmp_abc"):
        os.utime(cache_dir / name, (old, old))
    tts_cache.sweep(str(cache_dir), max_bytes=10**9)
    names = set(os.listdir(cache_dir))
    assert ("o" * 32 + ".ms") not in names and ("t" * 32 + ".mp3.tmp_abc") not in names
    assert "fresh.mp3.tmp_new" in names, "tệp tạm mới (đang ghi) không bị đụng"


def test_sweeper_gioi_han_tan_suat(cache_dir):
    sweeper = tts_cache.Sweeper(interval=3600)
    assert sweeper.maybe_run(str(cache_dir)) is True
    assert sweeper.maybe_run(str(cache_dir)) is False
    assert sweeper.maybe_run(str(cache_dir), force=True) is True


def test_tran_doc_tu_bien_moi_truong(monkeypatch):
    monkeypatch.setenv("TTS_CACHE_MAX_MB", "100")
    assert tts_cache.max_cache_bytes() == 100 * 1024 * 1024
    monkeypatch.setenv("TTS_CACHE_MAX_MB", "không phải số")
    assert tts_cache.max_cache_bytes() == 4096 * 1024 * 1024


def test_touch_cap_nhat_lan_dung_gan_nhat(cache_dir):
    _, path = _put(cache_dir, "t", 100, age=9999)
    before = os.stat(path).st_mtime
    tts_cache.touch(path)
    assert os.stat(path).st_mtime > before + 9000
