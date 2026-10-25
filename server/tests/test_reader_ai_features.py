"""Các tính năng AI khi đọc: companion, recap, lore, trắc nghiệm, đọc song ngữ dần, ngữ pháp.

Kết quả dự phòng phải gắn cờ is_fallback và KHÔNG bao giờ vào cache.
"""

import json

import pytest
from fastapi.testclient import TestClient

from server import app
from database import SqliteBackedTable
from routes.admin import get_configured_admin_key
from services import agy_runner
from services.auth_token import issue_token
from services.chapter_recap_service import chapter_recap_service, PriorChapter
from services.storage_service import storage_service
from services.agy_runner import TaskPriority
from services.companion_chat_service import companion_chat_service
from services.comprehension_generator import comprehension_generator, shuffle_options
from services.dictionary_cache import dictionary_cache
from services.grammar_extractor import grammar_service
from services.progressive_english import progressive_service
from services.translation_worker import translation_worker
from tests._fakes import isolated  # noqa: F401 (fixture)

client = TestClient(app)
AUTH = {"Authorization": f"Bearer {issue_token('user_reader_ai', 'reader')}"}


def _events(body: str):
    return [json.loads(line[6:]) for line in body.splitlines() if line.startswith("data: ")]


# ---------------------------------------------------------------------------
# Companion
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
async def test_companion_du_phong_gan_co_va_khong_bia(monkeypatch):
    monkeypatch.setattr(companion_chat_service, "_get_agy_path", lambda: None)
    res = await companion_chat_service.chat_with_companion("Truyện", "Chương 1", 1, "Ai là hung thủ?")
    assert res["is_fallback"] is True and res["error"]
    assert "phức tạp" not in res["reply"] and res["suggested_questions"] == []


@pytest.mark.asyncio
async def test_companion_prompt_chong_tiet_lo_va_goi_y_tu_cau_tra_loi(monkeypatch):
    seen = {}

    async def fake_json(prompt, timeout=30.0, priority=None, task_key=None):
        seen["prompt"] = prompt
        return {"reply": "Hắn đang giấu thân phận.", "suggested_questions": ["Vì sao hắn giấu?", 3, ""]}
    monkeypatch.setattr(companion_chat_service, "_get_agy_path", lambda: "/fake/agy")
    monkeypatch.setattr(companion_chat_service, "_run_agy_json", fake_json)
    res = await companion_chat_service.chat_with_companion("Truyện", "Chương 7", 7, "Hắn là ai?")
    assert res["is_fallback"] is False and res["error"] is None
    assert res["suggested_questions"] == ["Vì sao hắn giấu?"]
    assert "CHỐNG TIẾT LỘ" in seen["prompt"] and "chương 7" in seen["prompt"]


def test_companion_sse_done_co_co_du_phong(monkeypatch):
    rate_reset()
    monkeypatch.setattr(companion_chat_service, "_get_agy_path", lambda: None)
    res = client.post("/v1/companion/chat/stream", headers=AUTH, json={
        "novel_title": "Truyện", "user_message": "Chào", "chapter_number": None,
    })
    done = [e for e in _events(res.text) if e["type"] == "done"][0]
    assert done["is_fallback"] is True and done["error"] and done["suggested_questions"] == []


def test_companion_sse_that_khong_co_goi_y_co_dinh(monkeypatch):
    rate_reset()

    async def fake_stream(prompt, timeout=45.0, model=None, priority=None):
        for part in ("Xin ", "chào"):
            yield part
    monkeypatch.setattr(companion_chat_service, "_get_agy_path", lambda: "/fake/agy")
    monkeypatch.setattr(companion_chat_service, "_run_agy_stream", fake_stream)
    res = client.post("/v1/companion/chat/stream", headers=AUTH, json={
        "novel_title": "Truyện", "user_message": "Chào", "chapter_number": 3,
    })
    events = _events(res.text)
    assert "".join(e["content"] for e in events if e["type"] == "chunk") == "Xin chào"
    done = events[-1]
    assert done["is_fallback"] is False and done["error"] is None and done["suggested_questions"] == []


class _FakeStdout:
    def __init__(self, data: bytes):
        self._data = data

    async def read(self, n: int) -> bytes:
        chunk, self._data = self._data[:n], self._data[n:]
        return chunk


class _FakeProc:
    def __init__(self, data: bytes):
        self.stdout = _FakeStdout(data)
        self.returncode = None

    async def wait(self):
        self.returncode = 0
        return 0


@pytest.mark.asyncio
async def test_stream_nguoi_lanh_khong_rot_ky_tu_tieng_viet(monkeypatch):
    """Đọc 64 byte/lần cắt đôi ký tự nhiều byte; errors='ignore' từng vứt mất chúng."""
    text = "Tiếng Việt có dấu: ường, ượng, ẫm, ỡ, ặ — " * 6
    data = text.encode("utf-8")

    async def no_pool(*a, **k):
        if False:
            yield ""
    async def fake_exec(*cmd, **kwargs):
        return _FakeProc(data)

    from services.agy_pool import agy_pool
    monkeypatch.setattr(agy_pool, "execute_stream", no_pool)
    monkeypatch.setattr(companion_chat_service, "_get_agy_path", lambda: "/fake/agy")
    monkeypatch.setattr(agy_runner.asyncio, "create_subprocess_exec", fake_exec)
    out = "".join([c async for c in companion_chat_service._run_agy_stream("p", timeout=5)])
    assert out == text


# ---------------------------------------------------------------------------
# Recap + lore
# ---------------------------------------------------------------------------

def _fake_summaries(calls):
    async def fake_json(prompt, timeout=30.0, priority=None, task_key=None):
        calls.append({"prompt": prompt, "priority": priority})
        num = 8 if "CHƯƠNG 8" in prompt else 9
        return {"bullets": [f"Sự kiện A{num}", f"Sự kiện B{num}", f"Sự kiện C{num}"],
                "characters": ["Lâm Động (chính)"], "context": f"Cuối chương {num}"}
    return fake_json


@pytest.mark.asyncio
async def test_recap_tu_tom_tat_tung_chuong_co_cache(isolated, monkeypatch):
    calls = []
    monkeypatch.setattr(translation_worker, "_run_agy_json", _fake_summaries(calls))
    prior = [PriorChapter(8, "Nội dung chương tám " * 50), PriorChapter(9, "Nội dung chương chín " * 50)]
    recap = await chapter_recap_service.build_recap("n1", "Truyện", "Chương 10", prior)
    assert recap["is_fallback"] is False and recap["chapters_covered"] == [8, 9]
    assert "Sự kiện A8" in recap["bullets"], "Chương N-2 không được bị vứt"
    assert recap["context"] == "Cuối chương 9" and recap["characters"] == ["Lâm Động (chính)"]
    await chapter_recap_service.build_recap("n1", "Truyện", "Chương 10", prior)
    assert len(calls) == 2, "Lần mở sau phải dùng tóm tắt đã cache"
    # Làn tương tác, không tranh hai chỗ của làn đọc với bản dịch chương đang mở
    assert all(c["priority"] in (None, TaskPriority.INTERACTIVE) for c in calls)


@pytest.mark.asyncio
async def test_tom_tat_khong_mat_khi_ban_dich_doi(isolated, monkeypatch):
    """Khoá theo bản gốc: dịch lại hay đổi bản tạm Google -> AI không làm mất tóm tắt."""
    calls = []
    monkeypatch.setattr(translation_worker, "_run_agy_json", _fake_summaries(calls))
    original = "原文第九章。" * 40
    await chapter_recap_service.build_recap("n1", "T", "Ch", [PriorChapter(9, "Bản dịch cũ " * 30, original)])
    await chapter_recap_service.build_recap("n1", "T", "Ch", [PriorChapter(9, "Bản dịch MỚI " * 30, original)])
    assert len(calls) == 1


@pytest.mark.asyncio
async def test_recap_du_phong_gan_co_khong_cache(isolated, monkeypatch):
    async def fail_json(*a, **k):
        return None
    monkeypatch.setattr(translation_worker, "_run_agy_json", fail_json)
    recap = await chapter_recap_service.build_recap("n1", "Truyện", "Chương 3", [PriorChapter(2, "Nội dung")])
    assert recap["is_fallback"] is True and recap["error"]
    assert dictionary_cache.stats()["entries"] == 0


def test_recap_endpoint_dung_ban_dich_tot_bo_ban_tam(isolated, monkeypatch):
    rate_reset()
    seen = []

    async def fake_json(prompt, timeout=30.0, priority=None, task_key=None):
        seen.append(prompt)
        return {"bullets": ["Một sự kiện"], "characters": [], "context": "Bối cảnh"}
    monkeypatch.setattr(translation_worker, "_run_agy_json", fake_json)
    storage_service.save_novel_info("nr", {"title": "Truyện R"})
    storage_service.save_chapter("nr", 1, {"original_content": "原文一。" * 30, "translated_content": "Bản dịch AI một. " * 30})
    storage_service.save_chapter("nr", 2, {"original_content": "原文二。" * 30, "translated_content": "Bản Google hai. " * 30,
                                           "translation_engine": "GOOGLE_FALLBACK"})
    data = client.get("/v1/novels/nr/chapters/3/recap", headers=AUTH).json()
    assert data["is_fallback"] is False and data["error"] is None and data["chapters_covered"] == [1, 2]
    assert any("Bản dịch AI một" in p for p in seen), "Chương có bản AI thì tóm tắt từ bản dịch"
    assert not any("Bản Google hai" in p for p in seen), "Bản tạm Google không được đưa vào tóm tắt"
    first = client.get("/v1/novels/nr/chapters/1/recap", headers=AUTH).json()
    assert first["is_fallback"] is True, "Chương đầu không có chương trước: nội dung tĩnh, gắn cờ"


@pytest.mark.asyncio
async def test_lore_du_phong_gan_co(monkeypatch):
    monkeypatch.setattr(translation_worker, "_get_agy_path", lambda: None)
    res = await translation_worker.explain_lore_term("筑基")
    assert res["is_fallback"] is True and res["error"] and res["definition"] == ""

    async def ok_json(*a, **k):
        return {"term": "筑基", "definition": "Xây nền tu luyện."}
    monkeypatch.setattr(translation_worker, "_get_agy_path", lambda: "/fake/agy")
    monkeypatch.setattr(translation_worker, "_run_agy_json", ok_json)
    res = await translation_worker.explain_lore_term("筑基")
    assert res["is_fallback"] is False and res["error"] is None


# ---------------------------------------------------------------------------
# Trắc nghiệm đọc hiểu
# ---------------------------------------------------------------------------

def test_xao_dap_an_tat_dinh_va_giu_dung_dap_an():
    positions = set()
    for i in range(24):
        q = {"question": f"Câu {i}?", "options": ["Đúng", "Sai 1", "Sai 2", "Sai 3"], "correct_index": 0}
        a, b = shuffle_options(q), shuffle_options(q)
        assert a == b
        assert a["options"][a["correct_index"]] == "Đúng"
        positions.add(a["correct_index"])
    assert len(positions) >= 3, "Đáp án đúng không được luôn nằm ở A"


@pytest.mark.asyncio
async def test_bo_cau_hoi_heuristic_gan_co_khong_cache(isolated, monkeypatch):
    async def no_ai(*a, **k):
        return []
    monkeypatch.setattr(comprehension_generator, "_generate_with_ai", no_ai)
    res = await comprehension_generator.generate_questions("Ch 1", ["A sword clash in the valley."], "n1", 1)
    assert res["is_fallback"] is True and res["error"]
    assert dictionary_cache.stats()["entries"] == 0
    originals = comprehension_generator._generate_fallback("Ch 1", ["A sword clash in the valley."])
    for q, orig in zip(res["questions"], originals):
        assert q["options"][q["correct_index"]] == orig["options"][orig["correct_index"]]


@pytest.mark.asyncio
async def test_bo_cau_hoi_ai_duoc_xao_va_cache(isolated, monkeypatch):
    prompts = []

    async def fake_json(prompt, timeout=30.0, priority=None, task_key=None):
        prompts.append(prompt)
        return {"questions": [
            {"question": f"Q{i}?", "options": ["right", "w1", "w2", "w3"], "correct_index": 0, "explanation": "x"}
            for i in range(3)
        ] + [{"question": "bad", "options": ["a"], "correct_index": 5}]}
    monkeypatch.setattr(translation_worker, "_run_agy_json", fake_json)
    res = await comprehension_generator.generate_questions("Ch 2", ["Para one.", "Para two."], "n1", 2)
    assert res["is_fallback"] is False and len(res["questions"]) == 3
    assert all(q["options"][q["correct_index"]] == "right" for q in res["questions"])
    assert '"correct_index": 0' not in prompts[0]
    await comprehension_generator.generate_questions("Ch 2", ["Para one.", "Para two."], "n1", 2)
    assert len(prompts) == 1


# ---------------------------------------------------------------------------
# Đọc song ngữ dần, cache từ điển, ngữ pháp theo người dùng
# ---------------------------------------------------------------------------

@pytest.mark.parametrize("ratio,expected", [(0.25, 2), (0.5, 4), (0.75, 6), (1.0, 8)])
def test_ti_le_tieng_anh_dung_nhu_yeu_cau(ratio, expected):
    res = progressive_service.weave_chapter(["kiếm " * 8], [{"word": "sword", "meaning_vi": "kiếm"}], ratio)
    assert res["replaced_count"] == expected and res["total_candidates"] == 8


def test_xoa_cache_tu_dien_chi_danh_cho_quan_tri(monkeypatch):
    rate_reset()
    purged = []
    monkeypatch.setattr(dictionary_cache, "purge", lambda kind=None: purged.append(kind) or 0)
    monkeypatch.setenv("ADMIN_KEY", "k" * 48)
    assert client.delete("/v1/english/cache").status_code == 401
    assert client.delete("/v1/english/cache", headers=AUTH).status_code in (401, 403), \
        "Tài khoản thường không được xoá cache dùng chung"
    assert purged == []
    res = client.delete("/v1/english/cache", headers={"X-Admin-Key": get_configured_admin_key()})
    assert res.status_code == 200 and purged == [None]


def test_thong_ke_ngu_phap_tach_theo_nguoi_dung(tmp_path, monkeypatch):
    rate_reset()
    table = SqliteBackedTable("grammar_patterns", db_path=str(tmp_path / "g.db"))
    monkeypatch.setattr(grammar_service, "_get_table", lambda: table)
    text = "Not only did he survive the tribulation, but he also grew stronger."
    client.post("/v1/english/grammar-patterns", headers=AUTH, json={"text": text, "auto_record": True})
    mine = client.get("/v1/english/grammar-stats", headers=AUTH).json()
    other = client.get("/v1/english/grammar-stats",
                       headers={"Authorization": f"Bearer {issue_token('user_b', 'b')}"}).json()
    guest = client.get("/v1/english/grammar-stats").json()
    assert mine["total_occurrences"] >= 1
    assert other["total_occurrences"] == 0 and guest["total_occurrences"] == 0
    assert any(p["id"] == "negative_inversion" and p["frequency"] >= 1 for p in mine["patterns"])


def rate_reset():
    from services import rate_limit
    rate_limit.reset_all()
