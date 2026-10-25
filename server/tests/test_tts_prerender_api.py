"""POST /v1/tts/prerender: dựng giọng trước khi nghe — máy trạng thái, ưu tiên, nhận biết đoạn giọng máy, độ dẫn đầu."""

import threading

import pytest
from fastapi.testclient import TestClient

import routes.tts as tts_routes
from server import app
from services import tts_cache
from services.storage_service import storage_service
from services.tts_prerender import render_queue
from services.tts_prerender_plan import lead_needed_segments
from services.tts_segments import tts_texts
from tests._tts_fakes import FakeEngine, install, wait_until

client = TestClient(app, raise_server_exceptions=False)
NOVEL = "truyen-thu-nghiem"


@pytest.fixture(autouse=True)
def _polling_without_rate_limit(monkeypatch):
    """Test hỏi tiến độ mỗi 10 ms; giới hạn tần suất thật (60 lượt, nạp 1/giây) có test riêng ở test_tts_stream_rate_bucket."""
    from services import rate_limit
    monkeypatch.setitem(rate_limit._LIMITS, "tts_prerender", rate_limit.TokenBucket(100_000, 100_000.0, "tts_prerender"))


def paragraph(i: int, tag: str = "") -> str:
    return f"Đoạn {tag}số {i}. Lâm Phong chậm rãi ngẩng đầu, ánh mắt lạnh lẽo quét qua từng người trong đại điện."


def save_chapter(number: int, paragraphs=6, tag: str = "", **extra) -> str:
    text = "\n".join(paragraph(i, tag) for i in range(paragraphs))
    storage_service.save_chapter(NOVEL, number, {"title": f"Chương {number}", "translated_content": text, **extra})
    return text


def user_headers():
    from services.auth_token import issue_token
    return {"Authorization": f"Bearer {issue_token('tts_user', 'tts_user')}"}


def prerender(chapter=1, guest=False, **body):
    """Mặc định gọi như người đã đăng nhập (giữ nguyên priority); guest=True để thử đường khách."""
    payload = {"novel_id": NOVEL, "chapter_number": chapter, "voice": "mai_phuong", **body}
    res = client.post("/v1/tts/prerender", json=payload, headers=None if guest else user_headers())
    assert res.status_code == 200, res.text
    return res.json()


def first(chapter=1, **body):
    return prerender(chapter, **body)["chapters"][0]


def drain(timeout=5.0):
    assert wait_until(lambda: render_queue.length() == 0 and render_queue._running is None, timeout)


# ---------------------------------------------------------------- máy trạng thái

def test_chuong_chua_co_ban_dich_la_no_text_va_khong_xep_gi():
    engine = install()
    body = prerender(99)
    assert body["status"] == "ok" and body["chapters"] == [{**body["chapters"][0], "state": "no_text", "total_segments": 0,
                                                            "ready_segments": 0, "lead_needed_segments": 0, "chapter_number": 99}]
    assert render_queue.length() == 0 and engine.calls == []


def test_ban_dich_bi_tu_choi_hoac_con_sot_chu_han_cung_la_no_text():
    install()
    storage_service.save_chapter(NOVEL, 2, {"translated_content": "Tôi xin lỗi, nhưng tôi không thể dịch nội dung này."})
    storage_service.save_chapter(NOVEL, 3, {"translated_content": "这是一场注定碾压的死战" * 20})
    assert [c["state"] for c in prerender(2, count=2)["chapters"]] == ["no_text", "no_text"]


def test_di_tu_xep_hang_toi_dang_dung_toi_san_sang_va_goi_lai_la_hoi_tien_do():
    engine = install(FakeEngine(delay=0.01))
    texts = tts_texts(save_chapter(1, paragraphs=6))
    chapter = first(1)
    assert chapter["total_segments"] == 6 == chapter["segment_count"] and chapter["device_segments"] == 0
    assert chapter["state"] in ("queued", "rendering", "ready")
    assert wait_until(lambda: first(1)["state"] == "ready")
    done = first(1)
    assert done["ready_segments"] == done["total_segments"] == 6
    assert done["estimated_seconds_remaining"] == 0 and done["lead_needed_segments"] == 0 and done["can_start"] is True
    assert sorted(t for t, _ in engine.calls) == sorted(texts)
    calls = len(engine.calls)
    assert first(1)["state"] == "ready" and len(engine.calls) == calls, "gọi lại không dựng lại"


def test_khoa_cache_trung_voi_dung_nhung_gi_app_se_goi_o_stream():
    engine = install()
    text = save_chapter(1, paragraphs=4)
    assert wait_until(lambda: first(1)["state"] == "ready")
    calls = len(engine.calls)
    for seg in tts_texts(text):
        res = client.get("/v1/tts/stream", params={"text": seg, "voice": "mai_phuong", "lang": "vi"})
        assert res.status_code == 200
    assert len(engine.calls) == calls, "mọi đoạn app yêu cầu đều trúng cache dựng sẵn"


def test_may_chu_khoi_dong_lai_van_nhan_ra_cache_tren_dia():
    engine = install()
    save_chapter(1, paragraphs=3)
    assert wait_until(lambda: first(1)["state"] == "ready")
    render_queue.reset()
    from services.tts_local import local_tts
    local_tts.reset()  # engine chưa nạp lại
    chapter = first(1)
    assert chapter["state"] == "ready" and chapter["ready_segments"] == 3
    assert len(engine.calls) == 3


def test_giong_local_tat_thi_state_unavailable_de_app_dung_giong_may(monkeypatch):
    from services.tts_local import local_tts
    save_chapter(1, paragraphs=3)
    monkeypatch.setenv("TTS_LOCAL_ENABLED", "0")
    local_tts.start_warmup()
    chapter = first(1)
    assert chapter["state"] == "unavailable" and chapter["can_start"] is False
    assert render_queue.length() == 0


def test_engine_dang_nap_thi_state_queued_roi_tu_chay_khi_san_sang():
    from services.tts_local import LOADING, local_tts
    save_chapter(1, paragraphs=3)
    release, engine = threading.Event(), FakeEngine()

    def slow_factory(threads):
        release.wait(10)
        return engine

    local_tts.set_factory(slow_factory)
    chapter = first(1)  # lần đầu có người cần giọng local -> bắt đầu nạp ở nền (chế độ lười)
    assert local_tts.state == LOADING and chapter["state"] == "queued" and chapter["can_start"] is False
    release.set()
    assert wait_until(lambda: local_tts.is_ready())
    assert wait_until(lambda: first(1)["state"] == "ready")
    assert len(engine.calls) == 4 and engine.calls[0][0].startswith("Xin chào"), "3 mảnh + 1 lần làm nóng"


# ---------------------------------------------------------------- đoạn tiếng Trung do giọng máy đọc

def test_doan_tieng_trung_bao_la_device_va_khong_duoc_dung():
    engine = install()
    text = "\n".join([paragraph(0), "林风", paragraph(2), "***"])
    storage_service.save_chapter(NOVEL, 1, {"translated_content": text})
    assert wait_until(lambda: first(1)["state"] == "ready")
    chapter = first(1)
    assert chapter["segment_count"] == 4 and chapter["total_segments"] == 2
    assert chapter["device_segments"] == 1 and chapter["device_segment_indices"] == [1]
    assert chapter["skipped_segments"] == 1
    assert all("林" not in t for t, _ in engine.calls) and len(engine.calls) == 2


# ---------------------------------------------------------------- ưu tiên và vị trí bắt đầu

def test_chuong_dang_doc_chay_truoc_chuong_sap_toi_du_goi_sau():
    engine = FakeEngine()
    engine.gate = threading.Event()
    install(engine)
    save_chapter(1, 3, tag="A ")
    save_chapter(2, 3, tag="B ")
    save_chapter(3, 3, tag="C ")
    prerender(2, count=1, priority=2)
    assert wait_until(lambda: len(engine.started) == 1)
    prerender(3, count=1, priority=3)
    prerender(1, count=1, priority=1)
    engine.gate.set()
    drain()
    order = [t.split("số")[0].strip() for t in engine.started]
    assert order[0] == "Đoạn B"
    assert order[1:4] == ["Đoạn A"] * 3 and order[4:] == ["Đoạn B"] * 0 + ["Đoạn B", "Đoạn B", "Đoạn C", "Đoạn C", "Đoạn C"]


def test_nhieu_chuong_trong_mot_lan_goi_chuong_dau_theo_priority_cac_chuong_sau_thap_hon():
    engine = FakeEngine()
    engine.gate = threading.Event()
    install(engine)
    for n in range(1, 6):
        save_chapter(n, 2, tag=f"{n} ")
    body = prerender(1, count=5, priority=1)
    assert [c["chapter_number"] for c in body["chapters"]] == [1, 2, 3, 4, 5]
    assert wait_until(lambda: len(engine.started) == 1)
    levels = render_queue.queued_by_priority()  # chương 1 (2 mảnh, 1 đang chạy) p1; chương 2-3 p2; chương 4-5 p3
    assert levels == {1: 1, 2: 4, 3: 4}
    engine.gate.set()
    drain()


def test_khach_chua_dang_nhap_bi_ha_uu_tien_ve_it_nhat_2():
    engine = FakeEngine()
    engine.gate = threading.Event()
    install(engine)
    save_chapter(1, 3)
    prerender(1, guest=True, priority=1)  # không kèm token
    assert wait_until(lambda: len(engine.started) == 1)
    assert set(render_queue.queued_by_priority()) == {2}
    engine.gate.set()
    drain()


def test_nguoi_dang_nhap_giu_uu_tien_1():
    engine = FakeEngine()
    engine.gate = threading.Event()
    install(engine)
    save_chapter(1, 3)
    prerender(1, priority=1)
    assert wait_until(lambda: len(engine.started) == 1)
    assert set(render_queue.queued_by_priority()) == {1}
    engine.gate.set()
    drain()


def test_from_segment_dung_tu_manh_dang_nghe_roi_vong_ve_dau():
    engine = FakeEngine()
    engine.gate = threading.Event()
    install(engine)
    save_chapter(1, 6)
    chapter = first(1, from_segment=4)
    assert chapter["start_segment"] == 4
    assert wait_until(lambda: len(engine.started) == 1)
    engine.gate.set()
    drain()
    numbers = [int(t.split("số ")[1].split(".")[0]) for t in engine.started]
    assert numbers == [4, 5, 0, 1, 2, 3]


def test_ready_prefix_va_can_start_theo_manh_bat_dau():
    engine = FakeEngine()
    engine.gate = threading.Event()
    install(engine)
    save_chapter(1, 6)
    first(1, from_segment=2)
    assert wait_until(lambda: len(engine.started) == 1)
    chapter = first(1, from_segment=2)
    assert chapter["ready_prefix_segments"] == 0 and chapter["can_start"] is False
    engine.gate.set()
    assert wait_until(lambda: first(1, from_segment=2)["ready_prefix_segments"] >= 4)
    assert first(1, from_segment=2)["can_start"] is True


# ---------------------------------------------------------------- độ dẫn đầu để nghe liền mạch

def test_cong_thuc_do_dan_dau():
    # 10 mảnh x 10 giây, dựng chậm hơn phát 1.25 lần: cần 100*(1-1/1.25)*1.1 = 22 giây đệm -> 3 mảnh (+1 dự phòng)
    assert lead_needed_segments([10.0] * 10, 1.25, False) == 4
    assert lead_needed_segments([10.0] * 10, 1.0, False) == 0, "dựng kịp thời gian thực thì không cần dẫn đầu"
    assert lead_needed_segments([10.0] * 10, 0.4, False) == 0
    assert lead_needed_segments([10.0] * 10, 3.0, True) == 0, "đã dựng xong cả chương"
    assert lead_needed_segments([10.0] * 3, 100.0, False) == 3, "không bao giờ quá số mảnh"
    assert lead_needed_segments([], 2.0, False) == 0


def test_do_dan_dau_dung_rtf_do_duoc_va_giam_khi_dung_xong(monkeypatch):
    monkeypatch.setenv("TTS_ASSUMED_RTF", "2.0")
    engine = FakeEngine()
    engine.gate = threading.Event()
    install(engine)
    save_chapter(1, 8)
    chapter = first(1)
    assert 2 <= chapter["lead_needed_segments"] <= 8
    assert chapter["can_start"] is False and chapter["estimated_seconds_remaining"] > 0
    engine.gate.set()
    assert wait_until(lambda: first(1)["state"] == "ready")
    assert first(1)["lead_needed_segments"] == 0


# ---------------------------------------------------------------- trường văn bản theo lang

def test_lang_en_dung_truong_tieng_anh_va_original_dung_ban_goc():
    engine = install()
    storage_service.save_chapter(NOVEL, 1, {
        "translated_content": paragraph(0),
        "translated_content_en_c1": "The sun was setting behind the mountains when the old man finally spoke.",
        "original_content": "He looked at the sky and sighed for a long time.",
    })
    assert wait_until(lambda: first(1, lang="en")["state"] == "ready")
    assert wait_until(lambda: first(1, lang="original")["state"] == "ready")
    texts = [t for t, _ in engine.calls]
    assert "The sun was setting behind the mountains when the old man finally spoke." in texts
    assert "He looked at the sky and sighed for a long time." in texts and paragraph(0).split(".")[0] not in " ".join(texts)


def test_ban_song_ngu_va_goc_tieng_trung_khong_dung():
    install()
    storage_service.save_chapter(NOVEL, 1, {"translated_content": paragraph(0), "original_content": "林风缓缓抬起头。"})
    assert first(1, lang="bilingual")["state"] == "no_text"
    original = first(1, lang="original")  # toàn chữ Hán: giọng máy đọc, không dựng gì
    assert original["state"] == "ready" and original["total_segments"] == 0 and original["device_segments"] == 1


# ---------------------------------------------------------------- hàng đợi đầy và kiểm tra tham số

def test_hang_doi_day_xep_tung_phan_va_goi_lai_xep_tiep(monkeypatch):
    monkeypatch.setenv("TTS_QUEUE_MAX", "10")
    engine = FakeEngine(delay=0.005)
    engine.gate = threading.Event()
    install(engine)
    save_chapter(1, 25)
    chapter = first(1)
    assert chapter["total_segments"] == 25 and render_queue.length() <= 10
    engine.gate.set()
    assert wait_until(lambda: first(1)["state"] == "ready", timeout=10)
    assert first(1)["ready_segments"] == 25


@pytest.mark.parametrize("body", [
    {"count": 6}, {"count": 0}, {"priority": 0}, {"priority": 4}, {"chapter_number": 0}, {"from_segment": -1}, {"novel_id": ""},
])
def test_tham_so_sai_bi_tu_choi_422(body):
    payload = {"novel_id": NOVEL, "chapter_number": 1, "voice": "mai_phuong", **body}
    assert client.post("/v1/tts/prerender", json=payload).status_code == 422


def test_chi_dung_van_ban_da_luu_khong_nhan_van_ban_tuy_y():
    install()
    res = client.post("/v1/tts/prerender", json={"novel_id": NOVEL, "chapter_number": 1, "voice": "mai_phuong", "text": "văn bản tự do"})
    assert res.status_code == 200 and res.json()["chapters"][0]["state"] == "no_text"
    assert render_queue.length() == 0


def test_novel_id_nguy_hiem_khong_doc_ra_ngoai_kho_truyen():
    install()
    body = prerender(1, novel_id="../../etc/passwd")
    assert body["chapters"][0]["state"] == "no_text"
    assert tts_cache.usage(tts_routes.CACHE_DIR)[0] == 0


def test_engine_co_trong_thong_tin_phan_hoi():
    install()
    save_chapter(1, 1)
    engine = prerender(1)["engine"]
    assert engine["name"] == "vieneu" and engine["model_loaded"] is True
    assert {"queue_length", "avg_rtf", "state"} <= set(engine)
