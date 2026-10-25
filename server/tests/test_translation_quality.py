"""Chất lượng dịch chương: kiểm tra từ chối/cụt, dự phòng Google theo đoạn, pipeline worker.

Không gọi mạng, không gọi Antigravity CLI: mọi lời gọi ngoài đều được giả.
"""

import asyncio

import pytest

from services import chapter_fallback_translator as cft
from services import translation_cache_store
from services.fast_translator import FastTranslator, fast_translator, _text_cache_key
from services.agy_runner import TaskPriority
from services.translation_validation import (
    cjk_count,
    ENGINE_AI,
    ENGINE_FALLBACK,
    has_excess_cjk,
    is_refusal,
    is_truncated,
    paragraph_count,
    validate_ai_translation,
)
from services.translation_worker import translation_worker, AgyTimeoutError

SOURCE = "\n".join(f"第{i}段。林动握紧长剑，目光冷冽地望向远处的山门，心中暗暗发誓一定要变强。" for i in range(10))
GOOD_VI = "\n".join(
    f"Đoạn {i}. Lâm Động siết chặt thanh trường kiếm, ánh mắt lạnh lẽo nhìn về sơn môn phía xa, "
    f"trong lòng thầm thề nhất định phải trở nên mạnh mẽ." for i in range(10)
)


# ---------------------------------------------------------------------------
# Kiểm tra bản dịch
# ---------------------------------------------------------------------------

@pytest.mark.parametrize("text", [
    "The prompt could not be submitted because it may violate Google policy",
    "... Generative AI Prohibited Use policy ...",
    "I'm sorry, but I can't translate this content.",
    "Xin lỗi, tôi không thể dịch nội dung này.",
])
def test_nhan_dien_loi_tu_choi(text):
    assert is_refusal(text)


def test_loi_thoai_giua_chuong_khong_bi_coi_la_tu_choi():
    assert not is_refusal(GOOD_VI + "\n“Xin lỗi, tôi không thể đi cùng ngươi.”")


def test_vai_chu_han_le_thi_bo_qua_nhieu_thi_hong():
    assert not has_excess_cjk(GOOD_VI + " 林")
    assert has_excess_cjk(GOOD_VI[:100] + SOURCE)


def test_ban_dich_du_doan_khong_bi_coi_la_cut():
    assert not is_truncated(SOURCE, GOOD_VI, "vi")
    assert validate_ai_translation(SOURCE, GOOD_VI, "vi") is None


def test_thieu_doan_la_cut():
    three = "\n".join(GOOD_VI.splitlines()[:3])
    assert is_truncated(SOURCE, three, "vi")
    assert validate_ai_translation(SOURCE, three, "vi") == "truncated"


def test_ti_le_ky_tu_qua_thap_la_cut():
    long_block = "林动握紧长剑目光冷冽地望向远处" * 30
    assert is_truncated(long_block, "Lâm Động cầm kiếm.", "vi")


def test_nguon_ngat_dong_cung_bi_ai_gop_doan_khong_bi_coi_la_cut():
    """TXT nhập từ máy ngắt dòng cứng: AI gộp thành đoạn văn thật, số đoạn rụng mà bản dịch đủ."""
    hard_wrapped = "\n".join("林动握紧长剑，目光冷冽地" for _ in range(60))
    merged = "\n\n".join(" ".join(["Lâm Động siết chặt thanh trường kiếm, ánh mắt lạnh lẽo"] * 10) for _ in range(6))
    assert paragraph_count(merged) == 6 < 0.6 * paragraph_count(hard_wrapped)
    assert not is_truncated(hard_wrapped, merged, "vi")


def test_rung_so_doan_va_ngan_hon_han_la_cut():
    source = "\n".join("林动握紧长剑，目光冷冽地望向远处的山门，心中暗暗发誓一定要变强。" for _ in range(20))
    hanzi = cjk_count(source)
    para = ("Lâm Động siết chặt thanh trường kiếm, ánh mắt lạnh lẽo nhìn về phía xa. " * 3).strip()
    cut = "\n\n".join([para] * 8)  # còn 8/20 đoạn
    assert 2.5 <= len(cut) / hanzi < 3.4, "đúng vùng mềm: trên sàn cứng nhưng ngắn hơn hẳn chuẩn 4.3"
    assert is_truncated(source, cut, "vi")
    # Cùng độ dài nhưng đủ đoạn (AI viết gọn) thì không bị coi là cụt
    tight = "\n\n".join([para[: len(cut) // 20]] * 20)
    assert not is_truncated(source, tight, "vi")


def test_ngong_cung_la_hai_lan_chuan_do_thuc_te():
    """Chương dịch thật có 4.2-4.7 ký tự/chữ Hán; tóm tắt còn nửa (~2.15) vẫn phải bị bắt."""
    source = "林动握紧长剑。" * 100  # 600 chữ Hán
    assert not is_truncated(source, "x" * 2600, "vi")
    assert not is_truncated(source, "x" * 1600, "vi"), "Ngắn gọn nhưng còn trên sàn cứng"
    assert is_truncated(source, "x" * 1300, "vi")
    assert is_truncated(source, "x" * 2400, "bilingual")


def test_song_ngu_dem_doan_theo_cap():
    pairs = "\n".join(f"[EN] Line {i} with enough words to count properly.\n[VI] {ln}"
                      for i, ln in enumerate(GOOD_VI.splitlines()))
    assert not is_truncated(SOURCE, pairs, "bilingual")


# ---------------------------------------------------------------------------
# Dự phòng Google NGUYÊN CHƯƠNG
# ---------------------------------------------------------------------------

def _fake_google(record, fail_first=0, tl_prefix=True):
    state = {"fails": fail_first}

    async def fake(client, text, sl="auto", tl="vi"):
        record.append((tl, text))
        if state["fails"] > 0:
            state["fails"] -= 1
            return None
        return "\n".join(f"[{tl}] dịch {len(ln)}" for ln in text.split("\n"))
    return fake


def test_chia_khoi_khong_vuot_4500_va_giu_thu_tu():
    paragraphs = [("段" * 1000) + str(i) for i in range(12)] + ["长" * 9000]
    chunks = cft.build_chunks(paragraphs)
    assert all(len("\n".join(c)) <= cft.MAX_CHUNK_CHARS for c in chunks)
    flat = "".join("".join(c) for c in chunks)
    assert flat == "".join(paragraphs)


@pytest.mark.asyncio
async def test_du_phong_giu_cau_truc_doan(monkeypatch):
    calls = []
    monkeypatch.setattr(cft, "google_translate_raw", _fake_google(calls))
    text = "\n\n".join(("字" * 800) + str(i) for i in range(12))
    out = await cft.translate_chapter_text(None, text, "vi")
    assert len(calls) >= 3, "Chương dài phải chia nhiều khối"
    assert all(len(q) <= cft.MAX_CHUNK_CHARS for _, q in calls)
    assert len([ln for ln in out.split("\n") if ln.strip()]) == 12


@pytest.mark.asyncio
async def test_du_phong_thu_lai_moi_khoi_mot_lan(monkeypatch):
    calls = []
    monkeypatch.setattr(cft, "google_translate_raw", _fake_google(calls, fail_first=1))
    monkeypatch.setattr(cft.asyncio, "sleep", _no_sleep)
    out = await cft.translate_chapter_text(None, "第一段。\n第二段。", "vi")
    assert out and len(calls) == 2


@pytest.mark.asyncio
async def test_du_phong_that_bai_hai_lan_tra_none_khong_phien_am(monkeypatch):
    monkeypatch.setattr(cft, "google_translate_raw", _fake_google([], fail_first=10))
    monkeypatch.setattr(cft.asyncio, "sleep", _no_sleep)
    assert await cft.translate_chapter_text(None, "第一段。\n第二段。", "vi") is None


@pytest.mark.asyncio
async def test_song_ngu_ra_cap_en_vi(monkeypatch):
    monkeypatch.setattr(cft, "google_translate_raw", _fake_google([]))
    out = await cft.translate_chapter_text(None, "第一段。\n第二段。", "bilingual")
    assert out.count("[EN]") == 2 and out.count("[VI]") == 2
    assert "第" not in out, "Không bao giờ trả lại nguyên văn tiếng Trung cho song ngữ"


@pytest.mark.asyncio
async def test_method_fast_translator_khong_dung_nhanh_tua_de(monkeypatch):
    monkeypatch.setattr(cft, "google_translate_raw", _fake_google([]))

    # Ollama đã gỡ khỏi hệ thống: không còn đường nào gọi mô hình cục bộ
    assert not hasattr(fast_translator, "_query_ollama")
    out = await fast_translator.translate_chapter_text("第一段。\n第二段。", tl="vi")
    assert out.startswith("[vi]")


async def _no_sleep(*_a, **_k):
    return None


def test_cache_van_ban_dai_co_chan_tren_va_khong_giu_nguyen_van_dai():
    ft = FastTranslator()
    for i in range(700):
        ft._long_cache[f"k{i}"] = "v"
    assert len(ft._long_cache) == 500
    key = _text_cache_key("zh-CN", "vi", "字" * 5000)
    assert len(key) < 100


# ---------------------------------------------------------------------------
# Pipeline translation_worker.translate_chapter_detailed
# ---------------------------------------------------------------------------

@pytest.fixture
def worker_env(monkeypatch):
    env = {"ai": [], "ai_calls": 0, "writes": [], "reads": 0, "fallback_calls": 0, "cached": None}

    def fake_read(key, source, target):
        env["reads"] += 1
        return env["cached"]

    async def fake_ai(processed, glossary, custom_prompt, target_language, english_level, priority=None):
        env["ai_calls"] += 1
        nxt = env["ai"].pop(0) if env["ai"] else GOOD_VI
        if isinstance(nxt, Exception):
            raise nxt
        return nxt

    async def fake_fallback(text, tl="vi"):
        env["fallback_calls"] += 1
        return "BẢN GOOGLE"

    monkeypatch.setattr(translation_cache_store, "read", fake_read)
    monkeypatch.setattr(translation_cache_store, "write", lambda *a, **k: env["writes"].append(a))
    monkeypatch.setattr(translation_worker, "is_agy_ready", lambda *a, **k: True)
    monkeypatch.setattr(translation_worker, "_translate_via_agy_cli", fake_ai)
    monkeypatch.setattr(fast_translator, "translate_chapter_text", fake_fallback)
    return env


@pytest.mark.asyncio
async def test_tu_choi_thi_thu_lai_ai_mot_lan(worker_env):
    worker_env["ai"] = ["The prompt could not be submitted", GOOD_VI]
    res = await translation_worker.translate_chapter_detailed(SOURCE + "\nA")
    assert res.text == GOOD_VI and res.engine == ENGINE_AI and not res.is_fallback
    assert worker_env["ai_calls"] == 2 and len(worker_env["writes"]) == 1


@pytest.mark.asyncio
async def test_cut_hai_lan_thi_du_phong_va_khong_cache(worker_env):
    short = "\n".join(GOOD_VI.splitlines()[:2])
    worker_env["ai"] = [short, short]
    res = await translation_worker.translate_chapter_detailed(SOURCE + "\nB")
    assert res.is_fallback and res.engine == ENGINE_FALLBACK and res.text == "BẢN GOOGLE"
    assert worker_env["writes"] == [], "Bản dự phòng không bao giờ vào translation_cache"


@pytest.mark.asyncio
async def test_het_gio_khong_thu_lai(worker_env):
    worker_env["ai"] = [AgyTimeoutError("timeout"), GOOD_VI]
    res = await translation_worker.translate_chapter_detailed(SOURCE + "\nC")
    assert res.is_fallback and worker_env["ai_calls"] == 1


@pytest.mark.asyncio
async def test_force_bo_qua_translation_cache(worker_env):
    worker_env["cached"] = "BẢN CŨ TRONG CACHE"
    res = await translation_worker.translate_chapter_detailed(SOURCE + "\nD")
    assert res.text == "BẢN CŨ TRONG CACHE" and res.from_cache
    res = await translation_worker.translate_chapter_detailed(SOURCE + "\nD", force=True)
    assert res.text == GOOD_VI and not res.from_cache
    assert worker_env["reads"] == 1, "force=True không được đọc cache"
    assert len(worker_env["writes"]) == 1, "force=True ghi đè cache bằng bản mới"


@pytest.mark.asyncio
async def test_khong_cho_du_phong_thi_tra_none(worker_env):
    worker_env["ai"] = ["", ""]
    res = await translation_worker.translate_chapter_detailed(SOURCE + "\nE", allow_fallback=False)
    assert res is None and worker_env["fallback_calls"] == 0


@pytest.mark.asyncio
async def test_agy_chua_san_sang_dung_du_phong_chuong(worker_env, monkeypatch):
    monkeypatch.setattr(translation_worker, "is_agy_ready", lambda *a, **k: False)
    res = await translation_worker.translate_chapter_detailed(SOURCE + "\nF")
    assert res.is_fallback and worker_env["fallback_calls"] == 1 and worker_env["ai_calls"] == 0


@pytest.mark.asyncio
async def test_lan_nen_va_lan_doc_khong_gop_chung_mot_viec(worker_env, monkeypatch):
    """Độc giả mở chương mà bản dịch trước của nó còn xếp hàng ở làn nền: không chờ hết hàng nền."""
    lanes = []

    async def slow_ai(processed, glossary, custom_prompt, target_language, english_level, priority=None):
        lanes.append(priority)
        await asyncio.sleep(0.05)
        return GOOD_VI
    monkeypatch.setattr(translation_worker, "_translate_via_agy_cli", slow_ai)

    content = SOURCE + "\nG"
    bg, rd1, rd2 = await asyncio.gather(
        translation_worker.translate_chapter_detailed(content, priority=TaskPriority.BACKGROUND),
        translation_worker.translate_chapter_detailed(content, priority=TaskPriority.READER),
        translation_worker.translate_chapter_detailed(content, priority=TaskPriority.READER),
    )
    assert bg.text == rd1.text == rd2.text == GOOD_VI
    assert sorted(l.value for l in lanes) == ["background", "reader"], "Hai đọc cùng lúc gộp một, nền riêng một"


@pytest.mark.asyncio
async def test_chi_dua_thuat_ngu_co_trong_chuong_vao_prompt_va_khoa_cache(worker_env, monkeypatch):
    from models.translate import TermItem
    seen = []

    async def spy_ai(processed, glossary, custom_prompt, target_language, english_level, priority=None):
        seen.append([t.source for t in glossary])
        return GOOD_VI
    monkeypatch.setattr(translation_worker, "_translate_via_agy_cli", spy_ai)
    keys = []
    monkeypatch.setattr(translation_cache_store, "write", lambda key, *a, **k: keys.append(key))

    content = SOURCE + "\nH"
    await translation_worker.translate_chapter_detailed(content, glossary=[TermItem(source="林动", target="Lâm Động")])
    await translation_worker.translate_chapter_detailed(content, glossary=[
        TermItem(source="林动", target="Lâm Động"), TermItem(source="萧炎", target="Tiêu Viêm")], force=True)
    assert seen == [["林动"], ["林动"]], "萧炎 không có trong chương nên không vào prompt"
    assert keys[0] == keys[1], "Thêm tên riêng của chương khác không được đổi khoá cache"
