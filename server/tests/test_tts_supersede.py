"""Người dùng đổi ý (đổi giọng, mở chương khác) và chống chiếm chỗ: hàng đợi không bắt họ chờ thứ không còn dùng."""

import threading

import pytest
from fastapi.testclient import TestClient

import routes.tts as tts_routes
from server import app
from services.storage_service import storage_service
from services.tts_prerender import PRIORITY_BACKGROUND, PRIORITY_CURRENT, PRIORITY_UPCOMING, QueueFull, render_queue
from services.tts_voice_service import resolve_tts_plan
from tests._tts_fakes import FakeEngine, install, wait_until

client = TestClient(app, raise_server_exceptions=False)
NOVEL = "truyen-doi-y"


@pytest.fixture(autouse=True)
def _no_rate_limit(monkeypatch):
    from services import rate_limit
    for name in ("tts_prerender", "tts_stream"):
        monkeypatch.setitem(rate_limit._LIMITS, name, rate_limit.TokenBucket(100_000, 100_000.0, name))


def headers():
    from services.auth_token import issue_token
    return {"Authorization": f"Bearer {issue_token('tts_user', 'tts_user')}"}


def save(number, paragraphs=3):
    text = "\n".join(f"Chương {number} đoạn {i}. Gió thổi qua sân, Lâm Phong lặng im nhìn về phía núi." for i in range(paragraphs))
    storage_service.save_chapter(NOVEL, number, {"title": f"Chương {number}", "translated_content": text})


def prerender(number, voice, count=1, guest=False):
    res = client.post("/v1/tts/prerender", json={"novel_id": NOVEL, "chapter_number": number, "count": count, "voice": voice},
                      headers=None if guest else headers())
    assert res.status_code == 200, res.text
    return res.json()["chapters"][0]


def held_engine():
    engine = FakeEngine()
    engine.gate = threading.Event()
    return install(engine)


def drain():
    assert wait_until(lambda: render_queue.length() == 0 and render_queue._running is None, 10)


def test_doi_giong_thi_giong_moi_dung_truoc_va_giong_cu_xuong_nen():
    engine = held_engine()
    save(1)
    prerender(1, "my_duyen")  # mảnh 0 chạy (bị giữ), 2 mảnh còn chờ ở ưu tiên 1
    assert wait_until(lambda: len(engine.started) == 1)
    prerender(1, "duc_tri")  # người dùng đổi giọng ngay sau đó
    assert render_queue.queued_by_priority() == {PRIORITY_CURRENT: 3, PRIORITY_BACKGROUND: 2}
    engine.gate.set()
    drain()
    voices = [v for _, v in engine.calls]
    assert voices == ["Mỹ Duyên"] + ["Đức Trí"] * 3 + ["Mỹ Duyên"] * 2


def test_chuong_mo_sau_cung_duoc_dung_truoc_chuong_mo_truoc_do():
    engine = held_engine()
    for n in (1, 2, 3):
        save(n)
    prerender(1, "my_duyen")
    assert wait_until(lambda: len(engine.started) == 1)
    prerender(2, "my_duyen")
    prerender(3, "my_duyen")  # lật chương nhanh: chương đang mở là 3
    engine.gate.set()
    drain()
    order = [t.split(" đoạn")[0] for t, _ in engine.calls]
    assert order == ["Chương 1"] + ["Chương 3"] * 3 + ["Chương 2"] * 3 + ["Chương 1"] * 2


def test_hoi_tien_do_lap_lai_khong_xao_tron_thu_tu_trong_chuong():
    engine = held_engine()
    save(1, paragraphs=5)
    prerender(1, "my_duyen")
    assert wait_until(lambda: len(engine.started) == 1)
    for _ in range(5):
        prerender(1, "my_duyen")
    engine.gate.set()
    drain()
    assert [t.split(". ")[0] for t, _ in engine.calls] == [f"Chương 1 đoạn {i}" for i in range(5)]


def test_khach_khong_day_duoc_viec_cua_nguoi_khac_xuong():
    engine = held_engine()
    save(1)
    prerender(1, "my_duyen")
    assert wait_until(lambda: len(engine.started) == 1)
    prerender(1, "duc_tri", guest=True)
    assert render_queue.queued_by_priority() == {PRIORITY_CURRENT: 2, PRIORITY_UPCOMING: 3}
    engine.gate.set()
    drain()


def test_ai_con_can_giong_cu_thi_goi_lai_duoc_nang_len_lai():
    engine = held_engine()
    save(1)
    prerender(1, "my_duyen")
    assert wait_until(lambda: len(engine.started) == 1)
    prerender(1, "duc_tri")
    prerender(1, "my_duyen")  # đổi lại giọng cũ
    assert render_queue.queued_by_priority() == {PRIORITY_CURRENT: 2, PRIORITY_BACKGROUND: 3}
    engine.gate.set()
    drain()


def test_hang_doi_day_viec_quan_trong_hon_thay_cho_viec_nen(monkeypatch, tmp_path):
    monkeypatch.setenv("TTS_QUEUE_MAX", "10")
    engine = held_engine()
    cache = str(tmp_path)
    render_queue.submit(resolve_tts_plan("Giữ worker.", "my_duyen", "vi", cache), PRIORITY_CURRENT)
    assert wait_until(lambda: len(engine.started) == 1)
    background = [render_queue.submit(resolve_tts_plan(f"Nền {i}.", "my_duyen", "vi", cache), PRIORITY_BACKGROUND)
                  for i in range(10)]
    with pytest.raises(QueueFull):
        render_queue.submit(resolve_tts_plan("Nền thừa.", "my_duyen", "vi", cache), PRIORITY_BACKGROUND)
    current = render_queue.submit(resolve_tts_plan("Chương đang đọc.", "my_duyen", "vi", cache), PRIORITY_CURRENT)
    assert render_queue.length() == 10 and current.priority == PRIORITY_CURRENT
    with pytest.raises(QueueFull):
        background[-1].future.result(timeout=1)  # việc vào sau cùng ở mức thấp nhất nhường chỗ
    # bị thay chỗ không phải lỗi dựng: xếp lại được ngay, không phải chờ "thử lại sau"
    render_queue.submit(resolve_tts_plan("Nền 9.", "my_duyen", "vi", cache), PRIORITY_UPCOMING)
    engine.gate.set()


def test_mot_khach_khong_giu_het_cho_nghe_ngay(monkeypatch):
    install()
    monkeypatch.setitem(tts_routes._ondemand_inflight, "testclient", tts_routes.ondemand_per_client())
    res = client.get("/v1/tts/stream", params={"text": "Một đoạn chưa có trong bộ đệm.", "voice": "my_duyen"})
    assert res.status_code == 503 and res.json() == {"detail": "local_tts_unavailable"}
    assert res.headers["x-local-tts-reason"] == "busy"
    tts_routes._ondemand_inflight.clear()
    ok = client.get("/v1/tts/stream", params={"text": "Một đoạn chưa có trong bộ đệm.", "voice": "my_duyen"})
    assert ok.status_code == 200 and tts_routes._ondemand_inflight == {}
