"""Cache tựa đề bền vững + hàng đợi tinh chỉnh nền (dedup, ưu tiên, trần, chỉ chạy khi máy chủ mô hình sống)."""

import asyncio

import pytest

from services import title_refine_jobs
from services.title_cache_store import TitleCacheStore, title_cache
from services.title_mt_client import title_mt
from services.title_refiner import PRIORITY_BOOK, PRIORITY_CHAPTER, PRIORITY_SEARCH_BOOK, TitleRefiner


def test_cache_store_llm_dict_pending(tmp_path):
    store = TitleCacheStore(str(tmp_path / "c.sqlite"))
    assert store.get("book", "难哄") is None
    store.add_pending([("chapter", "国师的建议", "Quốc Sư đích đề nghị", ["n1"])])
    store.add_pending([("chapter", "国师的建议", "Quốc Sư đích đề nghị", ["n2", "n1"])])
    assert store.get("chapter", "国师的建议") == ("Quốc Sư đích đề nghị", "dict")
    assert store.refined("chapter", "国师的建议") is None
    assert store.pending() == [("chapter", "国师的建议", ["n1", "n2"])]
    assert store.put_llm("chapter", "国师的建议", "Lời khuyên của Quốc Sư")
    assert store.refined("chapter", "国师的建议") == "Lời khuyên của Quốc Sư" and store.pending() == []
    assert store.novels_for("chapter", "国师的建议") == ["n1", "n2"]
    store.add_pending([("book", "难哄", "Nan Hống", [])])
    store.mark_failed("book", "难哄")
    store.mark_failed("book", "难哄")
    assert store.get("book", "难哄") == ("Nan Hống", "final")
    # Mở lại tệp: dữ liệu còn nguyên (dùng chung, sống sót khi khởi động lại)
    again = TitleCacheStore(str(tmp_path / "c.sqlite"))
    assert again.refined("chapter", "国师的建议") == "Lời khuyên của Quốc Sư"
    assert again.counts() == {"chapter:llm": 1, "book:final": 1}


def test_cache_store_loi_dia_fail_soft(tmp_path):
    blocker = tmp_path / "file"
    blocker.write_text("x")
    store = TitleCacheStore(str(blocker / "sub" / "c.sqlite"))  # thư mục cha là một tệp: không mở được
    assert store.get("book", "x") is None and store.put_llm("book", "x", "y") is False


@pytest.fixture
def refiner(monkeypatch):
    monkeypatch.setenv("TITLE_MT_ENABLED", "1")
    r = TitleRefiner()
    yield r
    r.reset()


def test_enqueue_dedup_uu_tien_va_novel(refiner):
    assert refiner.enqueue("chapter", "a", "A", PRIORITY_CHAPTER, "n1")
    assert refiner.enqueue("chapter", "a", "A", PRIORITY_CHAPTER, "n2")
    assert refiner.enqueue("book", "b", "B", PRIORITY_BOOK)
    assert refiner.enqueue("book", "c", "C", PRIORITY_SEARCH_BOOK)
    assert refiner.enqueue("chapter", "a", "A", PRIORITY_SEARCH_BOOK)  # nâng ưu tiên
    assert len(refiner) == 3
    order = [refiner._pop().src for _ in range(3)]
    assert order[:2] == ["c", "a"] or order[:2] == ["a", "c"]
    assert order[2] == "b" and refiner._pop() is None


def test_enqueue_bo_qua_da_tinh_chinh_va_tat(refiner, monkeypatch):
    title_cache.put_llm("book", "x", "X")
    assert not refiner.enqueue("book", "x", "x", PRIORITY_BOOK)
    monkeypatch.setenv("TITLE_MT_ENABLED", "0")
    assert not refiner.enqueue("book", "y", "y", PRIORITY_BOOK)


def test_tran_hang_doi_bo_muc_kem_uu_tien(refiner, monkeypatch):
    monkeypatch.setenv("TITLE_REFINE_QUEUE_MAX", "2")
    assert refiner.enqueue("chapter", "c1", "", PRIORITY_CHAPTER)
    assert refiner.enqueue("chapter", "c2", "", PRIORITY_CHAPTER)
    assert not refiner.enqueue("chapter", "c3", "", PRIORITY_CHAPTER)  # đầy, cùng mức: từ chối
    assert refiner.enqueue("book", "b1", "", PRIORITY_SEARCH_BOOK)     # ưu tiên hơn: đẩy một tựa chương ra
    assert len(refiner) == 2 and ("book", "b1") in refiner._jobs and refiner.stats["dropped"] == 2


@pytest.mark.asyncio
async def test_process_ghi_cache_va_va_toc(refiner, monkeypatch):
    applied = []

    async def fake_refine(kind, src):
        return "Lời khuyên của Quốc Sư" if src == "国师的建议" else None
    async def up(max_age=None):
        return True
    monkeypatch.setattr(title_refine_jobs, "refine_one", fake_refine)
    monkeypatch.setattr(title_mt, "healthy", up)
    monkeypatch.setattr("services.title_patcher.apply_refined", lambda *a: applied.append(a) or 1)
    refiner.enqueue("chapter", "国师的建议", "Quốc Sư đích đề nghị", PRIORITY_CHAPTER, "n1")
    refiner.enqueue("chapter", "难", "Nan", PRIORITY_CHAPTER)
    await refiner._flush_unsaved()
    assert await refiner.process(refiner._pop()) == "Lời khuyên của Quốc Sư"
    assert title_cache.refined("chapter", "国师的建议") == "Lời khuyên của Quốc Sư"
    assert applied == [("chapter", "国师的建议", "Lời khuyên của Quốc Sư", ["n1"])]
    assert await refiner.process(refiner._pop()) is None
    assert refiner.stats == {"done": 1, "failed": 1, "dropped": 0} and refiner.generation == 1


@pytest.mark.asyncio
async def test_may_chu_mo_hinh_sap_thi_xep_lai_khong_tinh_that_bai(refiner, monkeypatch):
    async def none(kind, src):
        return None

    async def down(max_age=None):
        return False
    monkeypatch.setattr(title_refine_jobs, "refine_one", none)
    monkeypatch.setattr(title_mt, "healthy", down)
    refiner.enqueue("book", "b", "B", PRIORITY_BOOK)
    assert await refiner.process(refiner._pop()) is None
    assert ("book", "b") in refiner._jobs and refiner.stats["failed"] == 0


@pytest.mark.asyncio
async def test_worker_chi_chay_khi_may_chu_song(refiner, monkeypatch):
    calls = []

    async def fake_refine(kind, src):
        calls.append(src)
        return "X"
    healthy = {"ok": False}

    async def fake_healthy(max_age=None):
        return healthy["ok"]
    monkeypatch.setattr(title_refine_jobs, "refine_one", fake_refine)
    monkeypatch.setattr(title_mt, "healthy", fake_healthy)
    monkeypatch.setattr("services.title_patcher.apply_refined", lambda *a: 0)
    monkeypatch.setenv("TITLE_REFINE_PAUSE_MS", "1")
    real_sleep = asyncio.sleep

    async def short_sleep(s):
        await real_sleep(min(s, 0.01))
    monkeypatch.setattr("services.title_refiner.asyncio.sleep", short_sleep)
    refiner.enqueue("book", "b", "B", PRIORITY_BOOK)
    refiner.start()
    await real_sleep(0.1)
    assert calls == []  # máy chủ mô hình chưa sẵn sàng: không gửi gì
    healthy["ok"] = True
    for _ in range(50):
        if calls:
            break
        await real_sleep(0.02)
    await refiner.stop()
    assert calls == ["b"] and title_cache.refined("book", "b") == "X"


def test_restore_hang_doi_sau_khoi_dong_lai(refiner):
    title_cache.add_pending([("chapter", "s1", "S1", ["n1"]), ("book", "b1", "B1", [])])
    assert refiner.restore() == 2
    job = refiner._jobs[("chapter", "s1")]
    assert job.novels == {"n1"} and job.priority > PRIORITY_CHAPTER


@pytest.mark.asyncio
async def test_refine_one_sach_va_chuong(fixture_dict, monkeypatch):
    seen = []

    async def fake_zh_to_vi(text, hints=(), timeout=None, vn_check=None):
        seen.append((text, list(hints)))
        return {"我在大学当校长": "tôi làm hiệu trưởng ở đại học", "潜入兰利": "thâm nhập Langley"}.get(text)
    monkeypatch.setattr(title_mt, "zh_to_vi", fake_zh_to_vi)
    assert await title_refine_jobs.refine_one("book", "我在大学当校长") == "Tôi Làm Hiệu Trưởng Ở Đại Học"
    assert await title_refine_jobs.refine_one("chapter", "潜入兰利（上）") == "Thâm nhập Langley (thượng)"
    assert seen[1] == ("潜入兰利", [("兰利", "Langley")])


@pytest.mark.asyncio
async def test_nhuong_cpu_khi_co_nguoi_dang_nghe(refiner, monkeypatch):
    calls = []
    listening = {"on": True}

    async def fake_refine(kind, src):
        calls.append(src)
        return "X"

    async def up(max_age=None):
        return True
    monkeypatch.setattr(title_refine_jobs, "refine_one", fake_refine)
    monkeypatch.setattr(title_mt, "healthy", up)
    monkeypatch.setattr("services.title_refiner.tts_listener_waiting", lambda: listening["on"])
    monkeypatch.setattr("services.title_patcher.apply_refined", lambda *a: 0)
    real_sleep = asyncio.sleep

    async def short_sleep(s):
        await real_sleep(min(s, 0.01))
    monkeypatch.setattr("services.title_refiner.asyncio.sleep", short_sleep)
    refiner.enqueue("book", "b", "B", PRIORITY_BOOK)
    refiner.start()
    await real_sleep(0.1)
    assert calls == [] and refiner.stats.get("yielded", 0) > 0
    listening["on"] = False
    for _ in range(50):
        if calls:
            break
        await real_sleep(0.02)
    await refiner.stop()
    assert calls == ["b"]


def test_render_queue_listener_waiting():
    from services.tts_prerender import render_queue
    assert render_queue.listener_waiting() is False
