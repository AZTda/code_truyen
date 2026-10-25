"""Tự trích tên riêng sau mỗi chương dịch AI xong (auto glossary)."""

import asyncio
import json

import pytest

from models.translate import TermItem
from services.auto_glossary import AutoGlossaryService, parse_terms_payload, validate_terms
from services.glossary_store import GlossaryStore
from services.translation_worker import translation_worker

SOURCE = "林动握紧长剑，望向炎城的方向。青阳镇的人都说林动疯了。" * 3
TRANSLATION = "Lâm Động siết chặt trường kiếm, nhìn về phía Viêm Thành. Người Thanh Dương trấn đều nói Lâm Động điên rồi."


def test_chi_nhan_cap_xuat_hien_nguyen_van():
    raw = {"terms": [
        {"source": "林动", "target": "Lâm Động"},
        {"source": "炎城", "target": "Viêm Thành"},
        {"source": "萧炎", "target": "Tiêu Viêm"},        # nguồn không có trong bản gốc
        {"source": "青阳镇", "target": "Thanh Dương Trấn"},  # đích không có nguyên văn
        {"source": "林动", "target": "Lâm Động"},         # trùng
        {"source": "Lin", "target": "Lâm"},               # nguồn không phải chữ Hán
        "rác",
    ]}
    terms = validate_terms(raw, SOURCE, TRANSLATION)
    assert [(t.source, t.target) for t in terms] == [("林动", "Lâm Động"), ("炎城", "Viêm Thành")]


def test_gioi_han_25_muc():
    source = "".join(f"名{chr(0x4e00 + i)}" for i in range(40))
    translation = " ".join(f"Ten{i}" for i in range(40))
    raw = {"terms": [{"source": f"名{chr(0x4e00 + i)}", "target": f"Ten{i}"} for i in range(40)]}
    assert len(validate_terms(raw, source, translation)) == 25


def test_muc_auto_khong_de_muc_nguoi_dung(tmp_path):
    store = GlossaryStore(base_dir=str(tmp_path))
    store.save("n", [TermItem(source="林动", target="Lâm Động")])
    store.save("n", [TermItem(source="林动", target="Lin Dong"), TermItem(source="炎城", target="Viêm Thành")], auto=True)
    entries = {e["source"]: e for e in store.list_entries("n")}
    assert entries["林动"] == {"source": "林动", "target": "Lâm Động", "auto": False}
    assert entries["炎城"]["auto"] is True
    # Người dùng sửa lại mục auto thì mục đó thành mục thủ công
    store.save("n", [TermItem(source="炎城", target="Thành Viêm")])
    entries = {e["source"]: e for e in store.list_entries("n")}
    assert entries["炎城"] == {"source": "炎城", "target": "Thành Viêm", "auto": False}


def test_gioi_han_moi_lan_gui_va_do_dai(tmp_path):
    store = GlossaryStore(base_dir=str(tmp_path))
    many = [TermItem(source=f"字{i}", target=f"t{i}") for i in range(300)]
    assert store.save("n", many) == 200
    assert store.save("m", [TermItem(source="长" * 40, target="x"), TermItem(source="a", target="b" * 200)]) == 0


@pytest.mark.asyncio
async def test_trich_va_gop_voi_ai_gia(tmp_path, monkeypatch):
    store = GlossaryStore(base_dir=str(tmp_path))
    monkeypatch.setattr("services.auto_glossary.glossary_store", store)
    seen = {}

    async def fake_run_agy(prompt, timeout=30.0, model=None, priority=None, task_key=None):
        seen["priority"], seen["prompt"] = priority, prompt
        return "Đây: " + json.dumps({"terms": [{"source": "林动", "target": "Lâm Động"}]}, ensure_ascii=False)
    monkeypatch.setattr(translation_worker, "_run_agy", fake_run_agy)

    added = await AutoGlossaryService().extract_and_merge("n", 3, SOURCE, TRANSLATION)
    assert added == 1
    assert store.list_entries("n") == [{"source": "林动", "target": "Lâm Động", "auto": True}]
    assert seen["priority"].value == "background"


@pytest.mark.asyncio
async def test_len_lich_gop_theo_truyen_chuong(monkeypatch):
    service = AutoGlossaryService()
    runs = []

    async def fake_extract(novel_id, chapter_number, source, translation):
        runs.append((novel_id, chapter_number))
        return 0
    monkeypatch.setattr(service, "extract_and_merge", fake_extract)
    assert service.schedule("n", 1, SOURCE, TRANSLATION) is True
    assert service.schedule("n", 1, SOURCE, TRANSLATION) is False
    assert service.schedule("n", 2, "no cjk here", TRANSLATION) is False
    await asyncio.sleep(0)
    await asyncio.gather(*list(service._tasks))
    assert runs == [("n", 1)]


def test_chap_nhan_ca_mang_thuan_lan_object_lan_loi_dan():
    pair = {"source": "林动", "target": "Lâm Động"}
    as_array = "Đây là kết quả:\n```json\n" + json.dumps([pair], ensure_ascii=False) + "\n```\nHết."
    as_object = json.dumps({"terms": [pair]}, ensure_ascii=False)
    for raw in (as_array, as_object, "[Ghi chú] " + as_array):
        terms = validate_terms(parse_terms_payload(raw), SOURCE, TRANSLATION)
        assert [(t.source, t.target) for t in terms] == [("林动", "Lâm Động")], raw
    assert parse_terms_payload("không có json") is None
    assert validate_terms(parse_terms_payload("[1, 2]"), SOURCE, TRANSLATION) == []


@pytest.mark.asyncio
async def test_bang_day_van_tiep_tuc_hoc_va_truot_theo_chuong_moi(tmp_path, monkeypatch):
    store = GlossaryStore(base_dir=str(tmp_path))
    monkeypatch.setattr("services.auto_glossary.glossary_store", store)
    # 2 mục thủ công + 398 mục auto cũ = 400 mục (đầy trần MAX_TERMS_PER_NOVEL)
    store.save("full", [TermItem(source="手动一", target="Thủ Công 1"), TermItem(source="手动二", target="Thủ Công 2")], auto=False)
    for start in range(0, 398, 100):
        batch = [TermItem(source=f"旧名{i:03d}", target=f"Cu{i}") for i in range(start, min(start + 100, 398))]
        store.save("full", batch, auto=True)
    assert store.count("full") == 400

    service = AutoGlossaryService()

    async def no_extract(*args):
        return 0
    monkeypatch.setattr(service, "extract_and_merge", no_extract)
    assert service.schedule("full", 500, SOURCE, TRANSLATION) is True
    assert service.schedule("vacant", 1, SOURCE, TRANSLATION) is True
    await asyncio.gather(*list(service._tasks), return_exceptions=True)

    # Khi lưu thêm 3 thuật ngữ auto mới ở chương 500, giữ nguyên 2 mục thủ công + 395 mục auto gần nhất + 3 mục auto mới
    store.save(
        "full",
        [
            TermItem(source="新角甲", target="Tân Giác Giáp"),
            TermItem(source="新角乙", target="Tân Giác Ất"),
            TermItem(source="新角丙", target="Tân Giác Bính"),
        ],
        auto=True,
    )
    assert store.count("full") == 400
    sources = {t.source for t in store.load("full")}
    assert {"手动一", "手动二", "新角甲", "新角乙", "新角丙"} <= sources
    assert "旧名000" not in sources and "旧名001" not in sources and "旧名002" not in sources
