"""Vòng đời engine giọng local: nạp nền, lỗi + lùi thời gian, tắt bằng biến môi trường, chuẩn bị tệp mô hình."""

import os
import sys
import threading
import time
import types

import pytest

from services import tts_model_store
from services.tts_local import (
    DISABLED,
    FAILED,
    LOADING,
    READY,
    UNLOADED,
    WARMUP_TEXT,
    EngineUnavailable,
    LocalTts,
    local_tts,
    lower_thread_priority,
)
from tests._tts_fakes import FakeEngine, wait_until


@pytest.fixture
def warm(monkeypatch):
    monkeypatch.setenv("TTS_LOCAL_WARMUP", "1")


def test_nap_o_thread_nen_khong_chan_nguoi_goi_roi_lam_nong_engine(warm):
    release, engine = threading.Event(), FakeEngine(voices=["Mai Anh"])

    def slow_factory(threads):
        assert threads == 3, "TTS_LOCAL_THREADS mặc định là 3"
        release.wait(10)
        return engine

    local_tts.set_factory(slow_factory)
    started = time.monotonic()
    local_tts.start_warmup()
    assert time.monotonic() - started < 0.5, "start_warmup không được chờ mô hình"
    assert local_tts.state == LOADING and not local_tts.is_ready()
    with pytest.raises(EngineUnavailable) as caught:
        local_tts.synth_blocking("x", "Mai Anh")
    assert caught.value.reason == "loading"
    release.set()
    assert wait_until(local_tts.is_ready)
    assert engine.calls == [(WARMUP_TEXT, "Mai Anh")], "một lần suy luận làm nóng bằng giọng mặc định trước khi báo sẵn sàng"
    status = local_tts.status()
    assert status["model_loaded"] is True and status["state"] == READY and status["load_seconds"] is not None


def test_so_luong_thread_doc_tu_bien_moi_truong(warm, monkeypatch):
    seen = []
    monkeypatch.setenv("TTS_LOCAL_THREADS", "2")
    local_tts.set_factory(lambda threads: (seen.append(threads), FakeEngine())[1])
    local_tts.start_warmup()
    assert wait_until(local_tts.is_ready) and seen == [2]
    assert LocalTts.threads() == 2
    monkeypatch.setenv("TTS_LOCAL_THREADS", "nonsense")
    assert LocalTts.threads() == 3


def test_nap_loi_thi_failed_co_ly_do_va_503_khong_sap(warm):
    def broken(threads):
        raise ImportError("No module named 'vieneu'")

    local_tts.set_factory(broken)
    local_tts.start_warmup()
    assert wait_until(lambda: local_tts.state == FAILED)
    assert "vieneu" in local_tts.reason and not local_tts.is_ready()
    assert local_tts.unavailable().reason == "failed" and local_tts.unavailable().retry_after >= 30


def test_sau_loi_cho_het_thoi_gian_lui_moi_thu_lai(warm):
    attempts = []

    def flaky(threads):
        attempts.append(1)
        if len(attempts) == 1:
            raise RuntimeError("mạng đứt lúc tải mô hình")
        return FakeEngine()

    local_tts.set_factory(flaky)
    local_tts.start_warmup()
    assert wait_until(lambda: local_tts.state == FAILED)
    local_tts.start_warmup()
    local_tts.request()
    assert len(attempts) == 1, "chưa hết thời gian lùi thì không nạp lại"
    local_tts._retry_at = 0.0
    local_tts.start_warmup()
    assert wait_until(local_tts.is_ready) and len(attempts) == 2


def test_tat_bang_bien_moi_truong_khong_nap_gi(monkeypatch):
    calls = []
    monkeypatch.setenv("TTS_LOCAL_ENABLED", "0")
    local_tts.set_factory(lambda threads: calls.append(1))
    local_tts.start_warmup()
    local_tts.request()
    assert local_tts.state == DISABLED and calls == []
    assert local_tts.unavailable().reason == "disabled"
    assert local_tts.status()["enabled"] is False


@pytest.mark.parametrize("raw,enabled", [("1", True), ("true", True), ("0", False), ("false", False), ("off", False), ("", True)])
def test_cong_tat_bien_moi_truong(monkeypatch, raw, enabled):
    monkeypatch.setenv("TTS_LOCAL_ENABLED", raw)
    assert LocalTts.enabled() is enabled


def test_che_do_luoi_chi_nap_khi_co_nguoi_can():
    calls = []
    local_tts.set_factory(lambda threads: (calls.append(1), FakeEngine())[1])  # TTS_LOCAL_WARMUP=0 do fixture của bộ test
    local_tts.start_warmup()
    assert local_tts.state == UNLOADED and calls == []
    local_tts.request()
    assert wait_until(local_tts.is_ready) and calls == [1]


def test_nhieu_loi_lien_tiep_danh_dau_engine_hong_de_nap_lai():
    local_tts.install(FakeEngine())
    for _ in range(4):
        local_tts.report_failure(RuntimeError("x"))
    assert local_tts.is_ready()
    local_tts.report_success()  # một lần thành công xoá chuỗi lỗi
    for _ in range(4):
        local_tts.report_failure(RuntimeError("x"))
    assert local_tts.is_ready()
    local_tts.report_failure(RuntimeError("lần thứ năm"))
    assert local_tts.state == FAILED and not local_tts.is_ready() and "lần thứ năm" in local_tts.reason


def test_synth_blocking_chay_dung_engine_da_gan():
    engine = FakeEngine()
    local_tts.install(engine)
    wav, rate = local_tts.synth_blocking("Xin chào", "Mai Anh")
    assert rate == 24_000 and len(wav) > 0 and engine.calls == [("Xin chào", "Mai Anh")]


def test_mac_dinh_khong_co_goi_vieneu_thi_bao_failed_chu_khong_sap(warm):
    """Trong môi trường test gói vieneu không cài: nhà máy mặc định phải lỗi gọn (ImportError), không làm sập máy chủ."""
    local_tts.set_factory(None)
    try:
        import vieneu  # noqa: F401
        pytest.skip("gói vieneu có cài: không thử nạp mô hình thật trong bộ test")
    except ImportError:
        pass
    local_tts.start_warmup()
    assert wait_until(lambda: local_tts.state == FAILED, 10)
    assert local_tts.unavailable().reason == "failed"


def test_ha_do_uu_tien_thread_khong_nem_loi_o_moi_nen_tang():
    errors = []

    def run():
        try:
            lower_thread_priority(5)
            lower_thread_priority()
        except Exception as exc:  # pragma: no cover
            errors.append(exc)

    thread = threading.Thread(target=run)
    thread.start()
    thread.join()
    assert errors == []


@pytest.mark.skipif(not sys.platform.startswith("linux"), reason="nice theo thread chỉ có ở Linux")
def test_nice_chi_ap_cho_thread_hien_tai():  # pragma: no cover - chạy trong Docker/CI Linux
    before = os.getpriority(os.PRIO_PROCESS, threading.get_native_id())
    result = []

    def run():
        lower_thread_priority(7)
        result.append(os.getpriority(os.PRIO_PROCESS, threading.get_native_id()))

    t = threading.Thread(target=run)
    t.start()
    t.join()
    assert result[0] >= 7 and os.getpriority(os.PRIO_PROCESS, threading.get_native_id()) == before


def test_khoi_dong_may_chu_khong_cho_mo_hinh_va_health_van_nhanh(monkeypatch):
    """Lifespan bắt đầu nạp ở nền; /v1/health không bị chậm dù mô hình chưa nạp xong."""
    import server
    from fastapi.testclient import TestClient
    from services.agy_pool import agy_pool

    monkeypatch.setenv("TTS_LOCAL_WARMUP", "1")
    release = threading.Event()
    local_tts.set_factory(lambda threads: (release.wait(10), FakeEngine())[1])

    async def nothing(*args, **kwargs):
        return None

    for service in (server.auto_updater, server.cache_janitor):
        monkeypatch.setattr(service, "start", lambda *a, **k: None)
        monkeypatch.setattr(service, "stop", lambda *a, **k: None)
    monkeypatch.setattr(server.storage_service, "sync_all_to_database", lambda *a, **k: None)
    monkeypatch.setattr(agy_pool, "warmup", nothing)
    monkeypatch.setattr(agy_pool, "stop", nothing)
    monkeypatch.setattr(server, "checkpoint", lambda *a, **k: None)
    monkeypatch.setattr(server, "close_all_connections", lambda *a, **k: None)

    with TestClient(server.app) as client:
        started = time.monotonic()
        res = client.get("/v1/health")
        assert res.status_code == 200 and time.monotonic() - started < 2.0
        assert local_tts.state == LOADING
        release.set()
        assert wait_until(local_tts.is_ready)


@pytest.mark.parametrize("mode,expected", [
    ("v3nano", {"mode": "v3nano", "threads": 3, "onnx_dir": "/m/nano"}),
    ("v3turbo", {"mode": "v3turbo", "backend": "onnx", "device": "cpu", "threads": 3}),
])
def test_engine_that_tao_vieneu_dung_mo_hinh_va_nap_nano_tu_thu_muc_da_tai(monkeypatch, mode, expected):
    """Không nạp mô hình: giả gói vieneu, chỉ kiểm tra tham số máy chủ đưa cho Vieneu(...)."""
    from services import tts_local

    seen = {}

    class FakeTts:
        sample_rate = 24_000 if mode == "v3nano" else 48_000

        def list_preset_voices(self):
            return [("Mỹ Duyên — Nữ · Bắc · Phong cách đọc truyện", "Mỹ Duyên")]

    def fake_vieneu(**kwargs):
        seen.update(kwargs)
        return FakeTts()

    monkeypatch.setitem(sys.modules, "vieneu", types.SimpleNamespace(Vieneu=fake_vieneu))
    monkeypatch.setattr(tts_model_store, "ensure_model_files", lambda mode=None: {"model_dir": "/m/nano", "mode": mode})
    monkeypatch.setenv("TTS_LOCAL_MODEL", mode)
    engine = tts_local._default_factory(3)
    assert seen == expected and engine.mode == mode and engine.voices == ["Mỹ Duyên"]
    assert engine.sample_rate == FakeTts.sample_rate


def test_trang_thai_bao_mo_hinh_dang_chay(monkeypatch):
    assert local_tts.status()["model"] == "v3nano"
    monkeypatch.setenv("TTS_LOCAL_MODEL", "v3turbo")
    assert local_tts.status()["model"] == "v3turbo", "chưa nạp: báo mô hình sẽ nạp"
    engine = FakeEngine()
    engine.mode = "v3nano"
    local_tts.install(engine)
    assert local_tts.status()["model"] == "v3nano", "đã nạp: báo mô hình thật đang chạy"
