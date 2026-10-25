"""Hàng đợi dựng giọng: ưu tiên, loại trùng, chờ tối đa một đoạn, backpressure, lỗi và số đo RTF (engine giả)."""

import asyncio
import os
import threading

import pytest

from services import tts_cache
from services.tts_local import EngineUnavailable
from services.tts_prerender import (
    PRIORITY_BACKGROUND,
    PRIORITY_CURRENT,
    PRIORITY_ONDEMAND,
    PRIORITY_UPCOMING,
    JobFailed,
    QueueFull,
    render_queue,
)
from services.tts_voice_service import resolve_tts_plan
from tests._tts_fakes import FakeEngine, install, wait_until


@pytest.fixture
def cache_dir(tmp_path):
    path = tmp_path / "tts_queue_cache"
    path.mkdir()
    return str(path)


def plan(cache_dir, text, voice="mai_phuong"):
    return resolve_tts_plan(text, voice, "vi", cache_dir=cache_dir)


def blocked_engine(**kwargs):
    engine = FakeEngine(**kwargs)
    engine.gate = threading.Event()
    return install(engine)


def run_one(job):
    return job.future.result(timeout=5)


def test_chay_theo_thu_tu_uu_tien_0_roi_1_2_3(cache_dir):
    engine = blocked_engine()
    first = render_queue.submit(plan(cache_dir, "Việc đang chạy dở."), PRIORITY_BACKGROUND)
    assert wait_until(lambda: len(engine.started) == 1)
    names = {}
    for label, prio in (("nen", PRIORITY_BACKGROUND), ("sap toi", PRIORITY_UPCOMING), ("dang doc", PRIORITY_CURRENT), ("nghe ngay", PRIORITY_ONDEMAND)):
        names[label] = render_queue.submit(plan(cache_dir, f"Đoạn {label}."), prio)
    engine.gate.set()
    for job in names.values():
        run_one(job)
    assert engine.started[1:] == ["Đoạn nghe ngay.", "Đoạn dang doc.", "Đoạn sap toi.", "Đoạn nen."]
    assert run_one(first)


def test_trong_cung_uu_tien_thi_den_truoc_phuc_vu_truoc(cache_dir):
    engine = blocked_engine()
    render_queue.submit(plan(cache_dir, "Giữ worker."), PRIORITY_CURRENT)
    assert wait_until(lambda: len(engine.started) == 1)
    jobs = [render_queue.submit(plan(cache_dir, f"Đoạn {i}."), PRIORITY_CURRENT) for i in range(4)]
    engine.gate.set()
    [run_one(j) for j in jobs]
    assert engine.started[1:] == [f"Đoạn {i}." for i in range(4)]


def test_cung_mot_khoa_chi_dung_mot_lan_va_gop_nguoi_cho(cache_dir):
    engine = blocked_engine()
    a = render_queue.submit(plan(cache_dir, "Cùng một đoạn."), PRIORITY_CURRENT)
    b = render_queue.submit(plan(cache_dir, "Cùng một đoạn."), PRIORITY_BACKGROUND)
    assert a is b
    engine.gate.set()
    run_one(a)
    assert [t for t, _ in engine.calls] == ["Cùng một đoạn."]


def test_ca_hai_id_ho_so_cung_mot_giong_dung_chung_mot_viec(cache_dir):
    engine = blocked_engine()
    a = render_queue.submit(plan(cache_dir, "Đoạn dùng chung.", "mai_phuong"), PRIORITY_CURRENT)
    b = render_queue.submit(plan(cache_dir, "Đoạn dùng chung.", "google_std"), PRIORITY_CURRENT)
    assert a is b
    engine.gate.set()
    run_one(a)
    assert len(engine.calls) == 1


def test_viec_nen_duoc_nang_len_0_khi_nguoi_nghe_can_den_no(cache_dir):
    engine = blocked_engine()
    render_queue.submit(plan(cache_dir, "Giữ worker."), PRIORITY_CURRENT)
    assert wait_until(lambda: len(engine.started) == 1)
    others = [render_queue.submit(plan(cache_dir, f"Nền {i}."), PRIORITY_BACKGROUND) for i in range(5)]
    wanted = render_queue.submit(plan(cache_dir, "Nền 4."), PRIORITY_ONDEMAND)
    assert wanted is others[4]
    engine.gate.set()
    run_one(wanted)
    assert engine.started[1] == "Nền 4."


def test_viec_uu_tien_0_chi_phai_cho_toi_da_mot_doan(cache_dir):
    """Một việc nền đang chạy dở không bị ngắt, nhưng việc kế tiếp phải là việc ưu tiên 0."""
    engine = blocked_engine()
    running = render_queue.submit(plan(cache_dir, "Nền đang chạy."), PRIORITY_BACKGROUND)
    assert wait_until(lambda: engine.started == ["Nền đang chạy."])
    for i in range(10):
        render_queue.submit(plan(cache_dir, f"Nền xếp hàng {i}."), PRIORITY_BACKGROUND)
    urgent = render_queue.submit(plan(cache_dir, "Người nghe đang chờ."), PRIORITY_ONDEMAND)
    engine.gate.set()
    run_one(running)
    run_one(urgent)
    assert engine.started.index("Người nghe đang chờ.") == 1


def test_viec_uu_tien_0_khong_con_ai_cho_thi_ha_ve_1(cache_dir):
    engine = blocked_engine()
    render_queue.submit(plan(cache_dir, "Giữ worker."), PRIORITY_CURRENT)
    assert wait_until(lambda: len(engine.started) == 1)
    job = render_queue.submit(plan(cache_dir, "Bị bỏ giữa chừng."), PRIORITY_ONDEMAND)
    render_queue.attach(job)
    assert job.priority == PRIORITY_ONDEMAND
    render_queue.release(job)  # app huỷ yêu cầu
    assert job.priority == PRIORITY_CURRENT
    engine.gate.set()
    run_one(job)


def test_hang_doi_day_tu_choi_viec_thap_nhung_van_nhan_viec_nguoi_nghe(cache_dir, monkeypatch):
    monkeypatch.setenv("TTS_QUEUE_MAX", "10")
    engine = blocked_engine()
    render_queue.submit(plan(cache_dir, "Giữ worker."), PRIORITY_CURRENT)
    assert wait_until(lambda: len(engine.started) == 1)
    for i in range(10):
        render_queue.submit(plan(cache_dir, f"Chờ {i}."), PRIORITY_BACKGROUND)
    with pytest.raises(QueueFull):
        render_queue.submit(plan(cache_dir, "Dư một."), PRIORITY_BACKGROUND)
    urgent = render_queue.submit(plan(cache_dir, "Người nghe."), PRIORITY_ONDEMAND)
    assert urgent.priority == PRIORITY_ONDEMAND
    assert render_queue.length() == 11
    engine.gate.set()


def test_qua_nhieu_yeu_cau_nghe_truc_tiep_cung_luc_bi_tu_choi(cache_dir):
    engine = blocked_engine()
    render_queue.submit(plan(cache_dir, "Giữ worker."), PRIORITY_CURRENT)
    assert wait_until(lambda: len(engine.started) == 1)
    for i in range(24):
        render_queue.submit(plan(cache_dir, f"Nghe {i}."), PRIORITY_ONDEMAND)
    with pytest.raises(QueueFull):
        render_queue.submit(plan(cache_dir, "Nghe thứ 25."), PRIORITY_ONDEMAND)
    engine.gate.set()


def test_viec_da_co_trong_cache_khong_goi_engine(cache_dir):
    engine = install()
    p = plan(cache_dir, "Đã dựng từ trước.")
    tts_cache.write_audio(cache_dir, p.md5, b"mp3-cu", 1000)
    job = render_queue.submit(p, PRIORITY_ONDEMAND)
    assert run_one(job) == p.cache_file
    assert engine.calls == [] and open(p.cache_file, "rb").read() == b"mp3-cu"


def test_dung_xong_ghi_mp3_va_thoi_luong_tu_pcm(cache_dir):
    install(FakeEngine(seconds_per_char=0.1))
    text = "Mười ký tự."
    job = render_queue.submit(plan(cache_dir, text), PRIORITY_ONDEMAND)
    path = run_one(job)
    assert tts_cache.is_cached(path)
    expected_ms = round(len(plan(cache_dir, text).norm_text) * 0.1 * 1000, -1)
    assert abs(tts_cache.read_duration_ms(path) - expected_ms) <= 50


def test_loi_engine_lam_job_hong_roi_cho_thu_lai_sau(cache_dir, monkeypatch):
    engine = install(FakeEngine(fail_on={"HONG"}))
    job = render_queue.submit(plan(cache_dir, "Đoạn HỎNG HONG này."), PRIORITY_ONDEMAND)
    with pytest.raises(JobFailed):
        run_one(job)
    with pytest.raises(QueueFull):  # vừa lỗi: không đốt CPU dựng lại ngay
        render_queue.submit(plan(cache_dir, "Đoạn HỎNG HONG này."), PRIORITY_ONDEMAND)
    assert not os.path.exists(plan(cache_dir, "Đoạn HỎNG HONG này.").cache_file)
    assert [f for f in os.listdir(cache_dir) if ".tmp_" in f] == []
    assert engine.calls


def test_engine_chua_san_sang_thi_viec_nghe_ngay_bi_tu_choi_con_viec_nen_cho(cache_dir):
    background = render_queue.submit(plan(cache_dir, "Việc nền chờ engine."), PRIORITY_CURRENT)
    urgent = render_queue.submit(plan(cache_dir, "Người nghe."), PRIORITY_ONDEMAND)
    with pytest.raises(EngineUnavailable):
        urgent.future.result(timeout=5)
    assert not background.future.done()
    install()
    assert run_one(background)


@pytest.mark.asyncio
async def test_cho_het_gio_nem_unavailable_nhung_viec_van_chay_tiep(cache_dir):
    engine = blocked_engine()
    job = render_queue.submit(plan(cache_dir, "Quá chậm."), PRIORITY_ONDEMAND)
    with pytest.raises(EngineUnavailable) as caught:
        await render_queue.wait(job, timeout=0.05)
    assert caught.value.reason == "timeout"
    engine.gate.set()
    assert await asyncio.wait_for(asyncio.wrap_future(job.future), 5)
    assert tts_cache.is_cached(plan(cache_dir, "Quá chậm.").cache_file)


@pytest.mark.asyncio
async def test_nhieu_nguoi_cho_cung_mot_doan_deu_nhan_ket_qua(cache_dir):
    engine = blocked_engine()
    job = render_queue.submit(plan(cache_dir, "Nhiều người cùng nghe."), PRIORITY_ONDEMAND)
    waiters = [asyncio.create_task(render_queue.wait(job, 5)) for _ in range(3)]
    await asyncio.sleep(0.05)
    engine.gate.set()
    results = await asyncio.gather(*waiters)
    assert len(set(results)) == 1 and len(engine.calls) == 1


def test_so_do_rtf_va_giay_moi_ky_tu(cache_dir):
    install(FakeEngine(seconds_per_char=0.05, delay=0.02))
    for i in range(3):
        run_one(render_queue.submit(plan(cache_dir, f"Đoạn đo tốc độ số {i} khá dài một chút."), PRIORITY_CURRENT))
    stats = render_queue.stats
    assert stats.total == 3 and stats.rtf() is not None
    assert 0.03 < stats.seconds_per_char() < 0.08
    assert stats.effective_rtf() == max(stats.rtf(), 0.01)


def test_chua_do_thi_dung_rtf_gia_dinh(monkeypatch):
    monkeypatch.setenv("TTS_ASSUMED_RTF", "1.4")
    assert render_queue.stats.rtf() is None and render_queue.stats.effective_rtf() == 1.4


def test_rtf_va_giay_moi_ky_tu_gia_dinh_theo_mo_hinh(monkeypatch):
    """Nano dựng nhanh hơn thời gian thực trên MiniPC (đo 0,58-0,91), Turbo chậm hơn (~1,25)."""
    stats = render_queue.stats
    assert stats.effective_rtf() == 0.8 and stats.seconds_per_char() == 0.09
    monkeypatch.setenv("TTS_ASSUMED_RTF", "")  # .env để trống = theo mô hình
    assert stats.effective_rtf() == 0.8
    monkeypatch.setenv("TTS_ASSUMED_RTF", "abc")
    assert stats.effective_rtf() == 0.8
    monkeypatch.setenv("TTS_LOCAL_MODEL", "v3turbo")
    monkeypatch.delenv("TTS_ASSUMED_RTF")
    assert stats.effective_rtf() == 1.3 and stats.seconds_per_char() == 0.065


def test_ngu_canh_dem_so_doan_da_xong(cache_dir):
    install()
    ctx = ("truyen-1", 3, "Mai Anh")
    jobs = [render_queue.submit(plan(cache_dir, f"Đoạn {i}."), PRIORITY_CURRENT, ctx) for i in range(3)]
    [run_one(j) for j in jobs]
    assert wait_until(lambda: render_queue.context_done(ctx) == 3)
    assert render_queue.context_done(("truyen-khac", 1, "Mai Anh")) == 0


def test_backlog_chi_tinh_viec_co_uu_tien_cao_hon(cache_dir):
    engine = blocked_engine()
    render_queue.submit(plan(cache_dir, "Giữ worker."), PRIORITY_BACKGROUND)
    assert wait_until(lambda: len(engine.started) == 1)
    render_queue.submit(plan(cache_dir, "a" * 100), PRIORITY_CURRENT)
    render_queue.submit(plan(cache_dir, "b" * 100), PRIORITY_BACKGROUND)
    assert render_queue.backlog_seconds(PRIORITY_CURRENT) == 0
    assert render_queue.backlog_seconds(PRIORITY_UPCOMING) > 0
    assert render_queue.backlog_seconds(PRIORITY_BACKGROUND + 1) > render_queue.backlog_seconds(PRIORITY_UPCOMING)
    engine.gate.set()
