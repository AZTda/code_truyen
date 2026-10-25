"""Vá chapters_index.json / info.json khi bản tinh chỉnh về: chỉ thay giá trị máy sinh, không đụng bản dịch tốt."""

import json

import pytest

from services import title_patcher as P
from services.storage_service import storage_service
from services.title_cache_store import title_cache
from services.title_refiner import title_refiner


def _toc(novel_id, entries):
    storage_service.ensure_novel_dir(novel_id)
    storage_service.save_chapters_index(novel_id, entries)


def _read(novel_id):
    with open(storage_service.get_chapters_index_path(novel_id), encoding="utf-8") as f:
        return json.load(f)


ENTRIES = [
    {"chapter_number": 1, "id": "1", "title": "第1章 国师的建议", "title_vi": "Chương 1: Quốc Sư đích đề nghị"},
    {"chapter_number": 2, "id": "2", "title": "第2章 国师的建议", "title_vi": "Chương 2: Lời đề nghị của Quốc sư"},
    {"chapter_number": 3, "id": "3", "title": "第3章 国师的建议", "title_vi": ""},
    {"chapter_number": 4, "id": "4", "title": "第4章 国师的建议", "title_vi": "Chương 4: 国师的建议"},
    {"chapter_number": 5, "id": "5", "title": "第5章 林动出关", "title_vi": "Chương 5: Lâm Động xuất quan"},
]


def test_patch_toc_chi_thay_gia_tri_may_sinh(fixture_dict):
    _toc("n1", [dict(e) for e in ENTRIES])
    storage_service.save_chapter("n1", 1, {"title": "第1章 国师的建议", "title_vi": "Chương 1: Quốc Sư đích đề nghị"})
    storage_service.save_chapter("n1", 2, {"title": "第2章 国师的建议", "title_vi": "Chương 2: Lời đề nghị của Quốc sư"})
    assert P.patch_toc("n1", {"国师的建议": "Lời khuyên của Quốc Sư"}) == 3
    got = [e["title_vi"] for e in _read("n1")]
    assert got == ["Chương 1: Lời khuyên của Quốc Sư",
                   "Chương 2: Lời đề nghị của Quốc sư",  # bản dịch có sẵn (Google/AI/tay): giữ nguyên
                   "Chương 3: Lời khuyên của Quốc Sư",
                   "Chương 4: Lời khuyên của Quốc Sư",
                   "Chương 5: Lâm Động xuất quan"]
    assert storage_service.get_chapter("n1", 1)["title_vi"] == "Chương 1: Lời khuyên của Quốc Sư"
    assert storage_service.get_chapter("n1", 2)["title_vi"] == "Chương 2: Lời đề nghị của Quốc sư"


def test_patch_toc_khong_co_tep_hoac_hong(fixture_dict):
    assert P.patch_toc("khong-ton-tai", {"x": "y"}) == 0
    storage_service.ensure_novel_dir("hong")
    with open(storage_service.get_chapters_index_path("hong"), "w") as f:
        f.write("{khong phai json")
    assert P.patch_toc("hong", {"x": "y"}) == 0


def test_gia_tri_cu_tu_bang_nho_cung_duoc_thay(fixture_dict):
    from services.hanviet_dict import legacy_transliterate
    from services.title_extractor import format_chapter_title
    old = format_chapter_title(7, "第7章 圣兽战魂", legacy_transliterate("圣兽战魂"))
    assert P.chapter_title_replaceable(7, "第7章 圣兽战魂", old, "圣兽战魂")
    assert not P.chapter_title_replaceable(7, "第7章 圣兽战魂", "Chương 7: Chiến hồn thánh thú", "圣兽战魂")


def test_overlay_toc_gan_ban_tinh_chinh_va_xep_hang(fixture_dict, monkeypatch):
    monkeypatch.setenv("TITLE_MT_ENABLED", "1")
    title_cache.put_llm("chapter", "国师的建议", "Lời khuyên của Quốc Sư")
    chapters = [dict(e) for e in ENTRIES] + [
        {"chapter_number": 6, "id": "6", "title": "第6章 林动的建议", "title_vi": "Chương 6: Lâm Động đích đề nghị"}]
    assert P.overlay_toc("n2", chapters)
    assert chapters[0]["title_vi"] == "Chương 1: Lời khuyên của Quốc Sư"
    assert chapters[1]["title_vi"] == "Chương 2: Lời đề nghị của Quốc sư"
    assert ("chapter", "林动的建议") in title_refiner._jobs  # còn là bản convert, có 的 -> chờ mô hình
    assert title_refiner._jobs[("chapter", "林动的建议")].novels == {"n2"}
    assert not P.overlay_toc("n2", chapters)  # quét lại ngay: bỏ qua (chưa có bản tinh chỉnh mới)


def test_patch_book_info_json(fixture_dict):
    storage_service.save_novel_info("b1", {"title": "我在大学当校长", "title_vi": "Ngã Tại Đại Học Đương Hiệu Trưởng"})
    storage_service.save_novel_info("b2", {"title": "我在大学当校长", "title_vi": "Tôi Làm Hiệu Trưởng Đại Học"})
    assert P.patch_book("我在大学当校长", "Tôi Là Hiệu Trưởng Ở Đại Học") == 1
    assert storage_service.get_novel_info("b1")["title_vi"] == "Tôi Là Hiệu Trưởng Ở Đại Học"
    assert storage_service.get_novel_info("b2")["title_vi"] == "Tôi Làm Hiệu Trưởng Đại Học"


def test_apply_refined_dung_novel_ids_trong_cache(fixture_dict):
    _toc("n3", [dict(ENTRIES[0])])
    title_cache.add_pending([("chapter", "国师的建议", "x", ["n3"])])
    assert P.apply_refined("chapter", "国师的建议", "Lời khuyên của Quốc Sư", []) == 1
    assert _read("n3")[0]["title_vi"] == "Chương 1: Lời khuyên của Quốc Sư"


def test_overlay_novels_ket_qua_tim_kiem(fixture_dict):
    from models.novel import Novel
    title_cache.put_llm("book", "我在大学当校长", "Tôi Là Hiệu Trưởng Ở Đại Học")
    a = Novel(id="a", title="我在大学当校长", title_vi="Ngã Tại Đại Học Đương Hiệu Trưởng", url="u", source_id="s")
    b = Novel(id="b", title="我在大学当校长", title_vi="Bản dịch tay", url="u", source_id="s")
    P.overlay_novels([a, b])
    assert a.title_vi == "Tôi Là Hiệu Trưởng Ở Đại Học" and b.title_vi == "Bản dịch tay"
