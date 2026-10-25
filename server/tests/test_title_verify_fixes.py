"""Hồi quy cho các lỗi tìm thấy khi kiểm tra lại đường tựa đề không Google (hàng đợi, vá tệp, bản dịch cụt, từ khoá)."""

import time

import httpx
import pytest

from services import title_patcher as P
from services.storage_service import storage_service
from services.title_cache_store import title_cache
from services.title_mt_client import title_mt
from services.title_refiner import (PRIORITY_CHAPTER, PRIORITY_SEARCH_BOOK, PRIORITY_TOC_TAIL, TitleRefiner,
                                    title_refiner)


@pytest.fixture
def refiner(monkeypatch):
    monkeypatch.setenv("TITLE_MT_ENABLED", "1")
    r = TitleRefiner()
    yield r
    r.reset()


def test_hang_doi_day_khong_quet_toan_bo_moi_lan(refiner, monkeypatch):
    """Đầy 3000 mục cùng mức: mỗi tựa mới bị từ chối ngay (trước đây quét cả hàng đợi trên event loop)."""
    monkeypatch.setenv("TITLE_REFINE_QUEUE_MAX", "3000")
    monkeypatch.setattr(title_cache, "get", lambda kind, src: None)
    for i in range(3000):
        assert refiner.enqueue("chapter", f"c{i}", "", PRIORITY_CHAPTER)
    started = time.perf_counter()
    for i in range(3000):
        assert not refiner.enqueue("chapter", f"x{i}", "", PRIORITY_CHAPTER)
    assert time.perf_counter() - started < 0.5
    assert len(refiner) == 3000 and refiner.stats["dropped"] == 3000


def test_day_thi_day_muc_moi_nhat_cua_muc_kem_nhat(refiner, monkeypatch):
    monkeypatch.setenv("TITLE_REFINE_QUEUE_MAX", "3")
    refiner.enqueue("chapter", "head1", "", PRIORITY_CHAPTER)
    refiner.enqueue("chapter", "tail1", "", PRIORITY_TOC_TAIL)
    refiner.enqueue("chapter", "tail2", "", PRIORITY_TOC_TAIL)
    assert refiner.enqueue("chapter", "head2", "", PRIORITY_CHAPTER)  # đầu mục lục đẩy đuôi mục lục ra
    assert ("chapter", "tail2") not in refiner._jobs and ("chapter", "tail1") in refiner._jobs
    assert refiner.enqueue("book", "b", "", PRIORITY_SEARCH_BOOK)
    assert ("chapter", "tail1") not in refiner._jobs
    assert not refiner.enqueue("chapter", "tail3", "", PRIORITY_TOC_TAIL)
    assert [refiner._pop().src for _ in range(3)] == ["b", "head1", "head2"] and refiner._pop() is None


def test_nang_uu_tien_roi_bi_day_ra_van_nhat_quan(refiner, monkeypatch):
    monkeypatch.setenv("TITLE_REFINE_QUEUE_MAX", "2")
    refiner.enqueue("chapter", "a", "", PRIORITY_TOC_TAIL)
    refiner.enqueue("chapter", "b", "", PRIORITY_TOC_TAIL)
    refiner.enqueue("chapter", "a", "", PRIORITY_SEARCH_BOOK)  # a lên mức cao nhất
    assert refiner.enqueue("chapter", "c", "", PRIORITY_CHAPTER)  # đẩy b (mức đuôi), không đẩy a
    assert set(k[1] for k in refiner._jobs) == {"a", "c"}
    assert [refiner._pop().src for _ in range(2)] == ["a", "c"] and refiner._pop() is None


def test_heap_duoc_don_khi_nhieu_ban_ghi_cu(refiner, monkeypatch):
    monkeypatch.setenv("TITLE_REFINE_QUEUE_MAX", "1")
    monkeypatch.setattr(title_cache, "get", lambda kind, src: None)
    for i in range(3000):
        refiner.enqueue("chapter", f"t{i}", "", PRIORITY_TOC_TAIL)
        refiner.enqueue("book", f"b{i}", "", PRIORITY_SEARCH_BOOK)
        refiner._pop()
    assert len(refiner._heap) <= 4 * len(refiner._jobs) + 1001


def test_restore_nhan_hang_doc_san_va_ton_trong_tran(refiner, monkeypatch):
    monkeypatch.setenv("TITLE_REFINE_QUEUE_MAX", "2")
    rows = [("chapter", "s1", ["n1"]), ("chapter", "s2", []), ("chapter", "s3", [])]
    assert refiner.restore(rows) == 2 and len(refiner) == 2
    assert refiner._jobs[("chapter", "s1")].novels == {"n1"}


def test_translate_toc_duoi_muc_luc_kem_uu_tien(fixture_dict, monkeypatch):
    from services.title_service import translate_toc
    monkeypatch.setenv("TITLE_MT_ENABLED", "1")
    monkeypatch.setenv("TITLE_REFINE_TOC_SCAN", "1")
    translate_toc(["第1章 国师的建议", "第2章 林动的建议"], "n9")
    assert title_refiner._jobs[("chapter", "国师的建议")].priority == PRIORITY_CHAPTER
    job = title_refiner._jobs[("chapter", "林动的建议")]
    assert job.priority == PRIORITY_TOC_TAIL and job.novels == {"n9"}


def test_patch_book_chi_ghi_title_vi_tren_ban_moi_nhat(fixture_dict, monkeypatch):
    storage_service.save_novel_info("b1", {"title": "我在大学当校长", "title_vi": "Ngã Tại Đại Học Đương Hiệu Trưởng",
                                           "total_chapters": 10})
    real_list = storage_service.list_all_novels

    def stale_list():
        listed = real_list()
        # auto-updater cập nhật info.json SAU khi bản liệt kê đã được đọc
        storage_service.save_novel_info("b1", {"total_chapters": 12})
        return listed
    monkeypatch.setattr(storage_service, "list_all_novels", stale_list)
    assert P.patch_book("我在大学当校长", "Tôi Là Hiệu Trưởng Ở Đại Học") == 1
    info = storage_service.get_novel_info("b1")
    assert info["title_vi"] == "Tôi Là Hiệu Trưởng Ở Đại Học" and info["total_chapters"] == 12


def test_patch_toc_giu_khoa_khi_doc_ghi_tep_chuong(fixture_dict, monkeypatch):
    storage_service.ensure_novel_dir("n1")
    storage_service.save_chapters_index("n1", [{"chapter_number": 1, "id": "1", "title": "第1章 国师的建议",
                                                "title_vi": "Chương 1: Quốc Sư đích đề nghị"}])
    storage_service.save_chapter("n1", 1, {"title": "第1章 国师的建议", "title_vi": "Chương 1: Quốc Sư đích đề nghị",
                                            "content_vi": "Bản dịch chương."})
    held = []
    real_get = storage_service.get_chapter

    def spy_get(novel_id, num):
        held.append(storage_service._write_lock._is_owned())
        return real_get(novel_id, num)
    monkeypatch.setattr(storage_service, "get_chapter", spy_get)
    assert P.patch_toc("n1", {"国师的建议": "Lời khuyên của Quốc sư"}) == 1
    assert held and all(held)
    stored = real_get("n1", 1)
    assert stored["title_vi"] == "Chương 1: Lời khuyên của Quốc sư" and stored["content_vi"] == "Bản dịch chương."


def _mt_response(content, finish="stop"):
    return httpx.Response(200, json={"choices": [{"message": {"content": content}, "finish_reason": finish}]})


@pytest.mark.asyncio
async def test_ban_dich_bi_cut_vi_het_max_tokens_bi_bo(monkeypatch):
    monkeypatch.setenv("TITLE_MT_ENABLED", "1")
    replies = {"n": 0}

    def handler(request):
        replies["n"] += 1
        return _mt_response("Tôi làm hiệu trưởng ở", finish="length")
    await title_mt.aclose()
    monkeypatch.setattr(title_mt, "_client", httpx.AsyncClient(transport=httpx.MockTransport(handler)))
    try:
        assert await title_mt.zh_to_vi("我在大学当校长") is None
        assert await title_mt.vi_to_zh("ta ở đại học làm hiệu trưởng") is None
        before = replies["n"]
        out = await title_mt.zh_to_vi_batch(["国师的建议", "林动出关"])
        assert out == [None, None] and replies["n"] == before + 3  # lô cụt -> dịch lại từng mục
    finally:
        await title_mt.aclose()


@pytest.mark.asyncio
async def test_tu_khoa_han_viet_cache_mt_cu_xep_sau_tu_dien(fixture_dict):
    from services.query_translator import query_translator
    query_translator._cache["kiếm lai"] = ["来剑"]
    query_translator._cache_src["kiếm lai"] = "mt"
    try:
        out = await query_translator.translate_query("kiếm lai")
        assert out[0] == "剑来" and "来剑" in out
    finally:
        query_translator._cache.pop("kiếm lai", None)
        query_translator._cache_src.pop("kiếm lai", None)



def test_cache_hong_khong_mo_lai_cho_tung_tua(tmp_path, monkeypatch):
    from services import title_cache_store as S
    store = S.TitleCacheStore(str(tmp_path / "c.sqlite"))
    calls = []

    def broken_connect(*a, **k):
        calls.append(a)
        raise S.sqlite3.DatabaseError("file is not a database")
    monkeypatch.setattr(S.sqlite3, "connect", broken_connect)
    for i in range(200):
        assert store.get("chapter", f"t{i}") is None
    assert len(calls) == 1  # thử lại sau RETRY_SECONDS, không phải mỗi tựa một lần


def test_nguon_qua_dai_khong_xep_hang(refiner):
    assert not refiner.enqueue("book", "我" * 81, "", PRIORITY_SEARCH_BOOK)
    assert refiner.enqueue("book", "我" * 80, "", PRIORITY_SEARCH_BOOK)


def test_overlay_toc_quet_lai_toi_da_moi_phut_khi_hang_doi_chay(fixture_dict, monkeypatch):
    monkeypatch.setenv("TITLE_MT_ENABLED", "1")
    clock = {"t": 1000.0}
    monkeypatch.setattr(P.time, "monotonic", lambda: clock["t"])
    chapters = [{"chapter_number": 1, "id": "1", "title": "第1章 国师的建议",
                 "title_vi": "Chương 1: Quốc Sư đích đề nghị"}]
    P.overlay_toc("n5", chapters)
    title_cache.put_llm("chapter", "国师的建议", "Lời khuyên của Quốc Sư")
    title_refiner.generation += 1  # một tựa (bất kỳ) vừa tinh chỉnh xong
    clock["t"] += 5
    assert not P.overlay_toc("n5", chapters)  # chưa tới 1 phút: không quét lại 200 chương
    clock["t"] += 60
    assert P.overlay_toc("n5", chapters) and chapters[0]["title_vi"] == "Chương 1: Lời khuyên của Quốc Sư"
